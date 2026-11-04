package com.haojay.leapmotor.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感数据的加解密封装。
 *
 * 为什么不用 `EncryptedSharedPreferences`：它在 androidx.security 里已被标记废弃，
 * 且会额外带一个依赖。这里直接用 Android Keystore，代码不到 60 行，
 * 密钥由 TEE / StrongBox 持有、**永不导出**，进程内存里也拿不到原始密钥字节。
 *
 * 效果：
 * - 明文 `session.json` 被 `session.bin` 取代（AES-256-GCM，随机 IV 前置于密文）
 * - root 后拷走文件也解不开（密钥绑定本机 Keystore，换设备自然失效）
 * - 卸载/清数据会销毁密钥，密文随之作废 —— 这是设计如此，不是缺陷
 */
object SecureStore {

    private const val KEY_ALIAS = "leapmotor_local_aes"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** 取（首次则生成）本机密钥。密钥不出 Keystore。 */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不做用户认证绑定：车况轮询在后台跑，不能要求每次解锁指纹
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密。输出格式：[ivLen(1B)][iv][cipher+tag] */
    fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ByteArray(1 + iv.size + body.size).also { out ->
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            body.copyInto(out, 1 + iv.size)
        }
    }

    /** 解密；密钥失效/数据损坏时返回 null（调用方按"没有数据"处理）。 */
    fun decrypt(blob: ByteArray): String? = runCatching {
        val ivLen = blob[0].toInt()
        require(ivLen in 1..32) { "bad iv length" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val body = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()
}
