package com.haojay.leapmotor.data

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 零跑协议的加密/签名原语。
 *
 * 与 `ha-leapmotor-cn/poller/api_client.py` 逐函数对齐，算法均为实测反推：
 * - 签名串 = key 字典序排序后拼接 value
 * - 签名   = HMAC-SHA256(hkdfKey, 签名串) 的十六进制
 * - hkdfKey = b64url(r2) XOR b64url(r3) XOR b64url(JWT 第三段)
 */
object Crypto {

    /** 模拟 Python 的 str()，保证与参考实现逐字节一致。 */
    fun pyStr(v: Any?): String = when (v) {
        null -> "None"
        is Boolean -> if (v) "True" else "False"
        is Double -> if (v == v.toLong().toDouble()) v.toString() else v.toString()
        else -> v.toString()
    }

    /** 按 key 字典序排序后拼接所有 value。 */
    fun sortAndConcat(values: Map<String, Any?>): String =
        values.keys.sorted().joinToString("") { pyStr(values[it]) }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 宽容的 base64 解码：同时接受 url-safe（`-_`）与标准（`+/`）字符集，
     * 并按需补齐 padding。
     *
     * 实测必要：`signParam.r2/r3` 是**标准 base64**（含 `/`、`=`），而 JWT 段是
     * url-safe。若直接用 `getUrlDecoder()` 解码 r2，会抛
     * `Illegal base64 character 2f`（2f 即 `/`）。
     */
    fun base64UrlDecode(s: String): ByteArray {
        val std = s.replace('-', '+').replace('_', '/')
        val pad = (4 - std.length % 4) % 4
        return Base64.getDecoder().decode(std + "=".repeat(pad))
    }

    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** MD5 十六进制串的第 8~24 位，对应 App 的 ParamHeaderExt.getEncryptString。 */
    fun md5Mid16(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hex = toHex(md.digest(text.toByteArray(Charsets.UTF_8)))
        return hex.substring(8, 24)
    }

    fun hmacSha256Hex(key: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return toHex(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 签名密钥派生：HKDFKey = b64url(r2) XOR b64url(r3) XOR b64url(JWT 第三段)。
     * 长度取三段最小值（实测为 32 字节）。
     */
    fun deriveHkdfKey(carToken: String, r2: String, r3: String): String {
        val parts = carToken.split(".")
        require(parts.size == 3) { "carToken 不是合法 JWT（需要 3 段）" }
        val a = base64UrlDecode(r2)
        val b = base64UrlDecode(r3)
        val c = base64UrlDecode(parts[2])
        val n = minOf(a.size, b.size, c.size)
        val key = ByteArray(n) { i ->
            ((a[i].toInt() and 0xFF) xor (b[i].toInt() and 0xFF) xor (c[i].toInt() and 0xFF)).toByte()
        }
        return toHex(key)
    }

    /** 手机号加密：RSA-1024 PKCS#1 v1.5 → base64url 去 padding。 */
    fun rsaEncryptB64Url(text: String, publicKeyB64: String = Constants.RSA_PUBLIC_KEY): String {
        val der = Base64.getDecoder().decode(publicKeyB64)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return base64UrlEncode(ct)
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    // ---------------- 操作密码 ----------------

    private const val DEFAULT_OPERPWD_KEY = "f1cf0c025baec0e2"
    private const val DEFAULT_OPERPWD_IV = "6b6a1fe94e133fd7"

    /**
     * 由 token 派生 AES-128 key/iv。
     * CN 的账号 token 只有 32 字符（< 64），所以实际总是走内置默认值。
     */
    private fun operPwdKeyIv(token: String?): Pair<String, String> {
        if (token == null || token.length < 64) {
            return DEFAULT_OPERPWD_KEY to DEFAULT_OPERPWD_IV
        }
        val k = md5Mid16(token.substring(0, 32))
        val v = md5Mid16(token.substring(32, 64))
        return k to v
    }

    /**
     * 操作密码（4 位 PIN）编码：AES-128-CBC + PKCS7 + base64。
     * 明文或 MD5 都会被服务端判「操作密码错误」，必须用这个形态。
     */
    fun encryptOperatePwd(pin: String, token: String? = null): String {
        val (key, iv) = operPwdKeyIv(token)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            javax.crypto.spec.IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        val ct = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ct)
    }
}
