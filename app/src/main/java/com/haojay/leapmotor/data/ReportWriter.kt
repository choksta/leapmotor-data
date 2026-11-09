package com.haojay.leapmotor.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 用车报告的文案生成（DeepSeek，OpenAI 兼容接口）。
 *
 * 三条原则：
 * 1. **只喂真实统计**。模型拿到的 JSON 就是全部事实来源，提示词明确禁止编造
 *    天气、地名、路况这类它无从得知的东西 —— 之前那类"AI 报告"翻车都翻在这里。
 * 2. **语气靠提示词，不靠数据**。数据只负责"发生了什么"，情绪由模型组织。
 * 3. **Key 加密存本地**。与车控会话同一套 SecureStore，不上传、不写日志。
 */
object ReportWriter {

    private const val DEFAULT_BASE = "https://api.deepseek.com"
    private const val DEFAULT_MODEL = "deepseek-chat"
    private const val K_BASE = "report_base_url"
    private const val K_MODEL = "report_model"
    private const val K_KEY = "report_api_key"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // 生成 600~900 字，长尾要给足
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------------- 配置 ----------------

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(K_BASE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE

    fun model(ctx: Context): String =
        prefs(ctx).getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /** Key 以密文形式存放；读出失败（换机 / 清数据）时当作没配。 */
    fun apiKey(ctx: Context): String? {
        val b64 = prefs(ctx).getString(K_KEY, null) ?: return null
        val blob = runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }.getOrNull() ?: return null
        return SecureStore.decrypt(blob)?.takeIf { it.isNotBlank() }
    }

    fun saveConfig(ctx: Context, apiKey: String?, baseUrl: String?, model: String?) {
        prefs(ctx).edit().apply {
            if (apiKey != null) {
                if (apiKey.isBlank()) remove(K_KEY)
                else putString(K_KEY, android.util.Base64.encodeToString(SecureStore.encrypt(apiKey.trim()), android.util.Base64.NO_WRAP))
            }
            if (baseUrl != null) {
                val v = baseUrl.trim().trimEnd('/')
                if (v.isBlank() || v == DEFAULT_BASE) remove(K_BASE) else putString(K_BASE, v)
            }
            if (model != null) {
                val v = model.trim()
                if (v.isBlank() || v == DEFAULT_MODEL) remove(K_MODEL) else putString(K_MODEL, v)
            }
        }.apply()
    }

    fun hasKey(ctx: Context): Boolean = apiKey(ctx) != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("leapmotor_prefs", Context.MODE_PRIVATE)

    // ---------------- 生成 ----------------

    data class Report(val title: String, val body: String, val generatedAt: Long)

    /**
     * 生成一份报告。阻塞调用，必须在 IO 线程。
     *
     * @throws IllegalStateException 面向用户的可读错误（没配 Key、额度不足、网络问题）
     */
    fun generate(ctx: Context, stats: ReportStats.Stats, style: String = STYLE_WARM): Report {
        val key = apiKey(ctx) ?: throw IllegalStateException("还没填 API Key，点右上角设置填一个")
        val payload = buildPayload(stats)

        val body = JSONObject()
            .put("model", model(ctx))
            .put("temperature", 0.9)
            .put("max_tokens", 1200)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt(style)))
                put(JSONObject().put("role", "user").put("content", payload.toString(2)))
            })

        val req = Request.Builder()
            .url(baseUrl(ctx) + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(
                    when (resp.code) {
                        401 -> "API Key 无效或已过期"
                        402 -> "账户余额不足"
                        429 -> "请求太频繁，等一会儿再试"
                        in 500..599 -> "模型服务暂时不可用（${resp.code}）"
                        else -> msg ?: "生成失败（HTTP ${resp.code}）"
                    }
                )
            }
            val content = runCatching {
                JSONObject(raw).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
            }.getOrNull()?.trim().orEmpty()
            if (content.isBlank()) throw IllegalStateException("模型返回了空内容，稍后再试")

            return parse(content)
        }
    }

    /** 首行当标题（去掉 # 和引号），其余当正文。 */
    private fun parse(content: String): Report {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Report("用车报告", content, System.currentTimeMillis())
        val first = lines.first()
        val titleCandidate = first.removePrefix("#").trim().trim('*', '"', '「', '」')
        val isTitle = first.startsWith("#") || titleCandidate.length <= 24
        return if (isTitle && lines.size > 1) {
            Report(titleCandidate, lines.drop(1).joinToString("\n\n"), System.currentTimeMillis())
        } else {
            Report("用车报告", content, System.currentTimeMillis())
        }
    }

    const val STYLE_WARM = "warm"

    private fun systemPrompt(style: String): String {
        val tone = when (style) {
            else -> """
                你是一个"记得车主每段路"的朋友，语气温暖、具体、有分寸。
                要求：
                - 像在跟人聊天，不用"您"，不用敬语套话，不做总结陈词式的说教
                - 从数据里找细节说话（某一天跑得多、某几天没动车、充电习惯、出门时段），
                  不要泛泛而谈"请注意用车安全"这类没有信息量的话
                - 允许有一点情绪和想象，但**只能基于给定数据**：不许编造天气、地名、路况、
                  同行的人、目的地
                - 不夸张、不煽情、不用感叹号堆情绪，一句话里最多一个形容词
                - 电耗数据是"行驶电耗"（逐日明细只含行驶用电，不含空调与待机），
                  不要写成"综合电耗"，也不要拿它跟别的口径做对比
            """.trimIndent()
        }
        return tone + """

            输出格式（严格遵守）：
            第一行：一句标题，12 字以内，不加书名号，能概括这段时间的用车状态
            然后空一行，写 3 段正文，每段 60～90 字，段间空一行
            最后一行：一句不超过 20 字的收尾，可以是一个提醒或一个愿望
            不要输出 Markdown 列表、不要 emoji、不要分点。
        """.trimIndent()
    }

    private fun buildPayload(s: ReportStats.Stats): JSONObject {
        val payload = JSONObject()
            .put("区间", "${s.startDay} 至 ${s.endDay}（${s.periodLabel}）")
            .put("总里程_km", s.totalKm)
            .put("总电耗_kWh", s.totalKwh)
            .put("行驶电耗_kWh每百公里", s.ecPer100 ?: "云端未提供")
            .put("用车天数", s.driveDays)
            .put("区间总天数", s.totalDays)
            .put("没动车天数", s.restDays)
            .put("日均里程_km", s.avgDailyKm)
            .put("充电次数", s.chargeCount)
            .put("单日最高里程_km", s.longestDayKm)
            .put("单日最高里程日期", s.longestDayDate)
        if (s.busiestStartHour >= 0) payload.put("通常几点出门", "${s.busiestStartHour} 点前后")
        if (s.busiestEndHour >= 0) payload.put("通常几点收车", "${s.busiestEndHour} 点前后")

        ReportStats.changePercent(s.totalKm, s.prevKm)?.let {
            payload.put("与上一周期相比_里程变化百分比", it)
        }
        s.prevChargeCount?.let { payload.put("上一周期充电次数", it) }

        payload.put("里程最高的几天", JSONArray().apply {
            s.topDays.forEach { (day, km, charges) ->
                put(JSONObject().put("日期", day).put("里程_km", km).put("当天充电次数", charges))
            }
        })
        return payload
    }
}
