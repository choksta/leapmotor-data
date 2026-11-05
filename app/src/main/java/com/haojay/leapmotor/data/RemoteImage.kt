package com.haojay.leapmotor.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拉一张网络图。
 *
 * 没引入 Coil/Glide：整个 App 只有「驻车照片」一处要显示远程图，为一张图加一个
 * 图片加载框架（含缓存、生命周期、磁盘策略）不划算。
 *
 * 两个必须处理的坑：
 * 1. **签名地址是 http**，而 `targetSdk 31+` 默认禁明文。OSS 的 V1 签名不包含协议部分，
 *    所以直接把 scheme 换成 https 也能过校验——先试 https，失败再回落到原始 http
 *    （回落这条要有 `network_security_config` 放行，见 `res/xml/network_security_config.xml`）。
 * 2. **必须限流下载体积**。签名地址指向原图，几 MB 的图在弱网下会把内存和流量一起吃掉，
 *    这里先按采样率读一遍尺寸，再按目标宽度二次解码。
 *
 * 阻塞调用，只能在 IO 线程执行。
 */
object RemoteImage {

    private const val MAX_BYTES = 12 * 1024 * 1024

    fun load(url: String, targetWidthPx: Int = 1080): Bitmap? {
        val candidates = buildList {
            if (url.startsWith("http://")) add("https://" + url.removePrefix("http://"))
            add(url)
        }
        for (u in candidates) {
            val bytes = runCatching { download(u) }.getOrNull() ?: continue
            decode(bytes, targetWidthPx)?.let { return it }
        }
        return null
    }

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val len = conn.contentLengthLong
            if (len > MAX_BYTES) return null
            return conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, targetWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > targetWidthPx * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}
