package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 Python 参考实现逐项比对的基准测试。
 * 期望值全部来自 `ha-leapmotor-cn/poller/api_client.py` 的真实输出。
 */
class CryptoTest {

    @Test
    fun md5Mid16_已知向量() {
        assertEquals("c0f1b6a831c399e2", Crypto.md5Mid16("a"))
        assertEquals("3cd24fb0d6963f7d", Crypto.md5Mid16("abc"))
    }

    @Test
    fun sortAndConcat_按键名排序() {
        assertEquals("123", Crypto.sortAndConcat(mapOf("b" to "2", "a" to "1", "c" to "3")))
    }

    /**
     * 用合成向量验证派生逻辑，不掺任何真实会话。
     * carToken 的签名段是 32 字节的 0x00..0x1F，r2/r3 分别是 0x20..0x3F 与 0x40..0x5F，
     * 三段按位异或后正好是 0x60..0x7F，期望值可以手算核对。
     */
    @Test
    fun deriveHkdfKey_合成向量() {
        val carToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJ1c2VyX25hbWUiOiJhY2NvdW50SWQ6MCwxLGRldmljZUlkOm51bGwiLCJleHAiOjB9." +
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val r2 = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
        val r3 = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
        assertEquals(
            "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f",
            Crypto.deriveHkdfKey(carToken, r2, r3)
        )
    }

    @Test
    fun hmacSha256_已知向量() {
        // RFC 4231 Test Case 2
        val key = "Jefe".toByteArray(Charsets.UTF_8)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Crypto.hmacSha256Hex(key, "what do ya want for nothing?")
        )
    }

    @Test
    fun rsa加密_产出171字符() {
        // 用占位号码，不要写真实手机号进仓库
        val ct = Crypto.rsaEncryptB64Url("13800138000")
        assertEquals(171, ct.length)
        assertEquals(false, ct.contains("="))
    }

    /** signParam 的 r2/r3 是标准 base64（可能含 + / 与 =），必须能解码。 */
    @Test
    fun base64解码_兼容标准与urlsafe() {
        // 故意构造一个同时含 '+' 与 '/' 的标准 base64，这是登录接口真实返回的形态
        val standard = "+/++7z4/vu8+P77vPj++7z4/vu8+P77vPj++7z4/vu8="
        assertEquals(32, Crypto.base64UrlDecode(standard).size)

        // url-safe 形态也要能解
        val urlsafe = standard.replace('+', '-').replace('/', '_')
        assertEquals(32, Crypto.base64UrlDecode(urlsafe).size)

        // 两种写法解出来必须一致
        assertEquals(
            Crypto.base64UrlDecode(standard).toList(),
            Crypto.base64UrlDecode(urlsafe).toList()
        )
    }
}
