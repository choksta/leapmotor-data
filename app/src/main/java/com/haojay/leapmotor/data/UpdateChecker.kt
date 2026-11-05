package com.haojay.leapmotor.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一个已发布的版本。 */
data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSizeMb: Double,
    val publishedAt: String,
)

/**
 * 从 GitHub Releases 检查更新。
 *
 * 仓库已公开，直接匿名请求 `releases/latest` 即可，**不需要任何令牌**。
 * 之前那一版要配只读令牌，是因为仓库私有；现在这条限制没有了，
 * 相应地把令牌输入、加密保存、校验失败提示一并去掉——少一个需要用户理解的概念。
 *
 * 注意 GitHub 对匿名请求有速率限制（每 IP 每小时 60 次），
 * 所以调用方要按设置里的间隔节流，不要在每次进入页面时都请求。
 */
object UpdateChecker {

    const val REPO = "choksta/leapmotor-data"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉最新 release。阻塞调用，必须在 IO 线程。 */
    fun fetchLatest(): Result<AppRelease> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    when (resp.code) {
                        403, 429 -> "请求过于频繁，请稍后再试"
                        404 -> "还没有发布任何版本"
                        in 500..599 -> "GitHub 暂时不可用（${resp.code}）"
                        else -> "GitHub 返回 ${resp.code}"
                    }
                )
            }

            val o = JSONObject(raw)
            val tag = o.optString("tag_name")
            if (tag.isEmpty()) throw IllegalStateException("版本信息为空")

            var apkUrl: String? = null
            var size = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").takeIf { it.isNotEmpty() }
                        size = a.optLong("size")
                        break
                    }
                }
            }

            AppRelease(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                notes = o.optString("body"),
                pageUrl = o.optString("html_url").ifEmpty { RELEASES_PAGE },
                apkUrl = apkUrl,
                apkSizeMb = size / 1024.0 / 1024.0,
                publishedAt = o.optString("published_at"),
            )
        }
    }

    /**
     * 语义化版本比较：a 比 b 新返回正数。
     *
     * 只认数字段（`0.3.1` / `v1.2` / `1.0.0-beta` 都能比），
     * 预发布后缀按"段数少者视为 0"处理，够用且不会因为 `-beta` 报错。
     */
    fun compare(a: String, b: String): Int {
        fun parts(s: String): List<Int> = s.trim()
            .removePrefix("v").removePrefix("V")
            .split('.', '-', '+', '_')
            .mapNotNull { it.toIntOrNull() }

        val pa = parts(a)
        val pb = parts(b)
        if (pa.isEmpty() || pb.isEmpty()) return 0
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
