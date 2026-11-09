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
            .put("max_tokens", 2400)
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

    private fun systemPrompt(style: String): String = """
        你是一个"记得车主每段路"的朋友，语气温暖、具体、有分寸。

        这次要写一份 600~800 字的用车回望，不是摘要。判断标准只有一条：
        读完能不能想起具体是哪几天、为什么那几天特别。

        硬性要求：
        1. **每个段落至少挂一个具体日期和具体数字**，不许只写"这段时间跑了多少"。
           逐日数据里哪些天有里程、哪些天是 0，都要用上。
        2. 必须写到下面这些里至少四件事，各自有独立的一段：
           - 跑得最多的那一天（具体日期、多少公里、当天是否充过电）
           - 最长的一段没动车的日子（从哪天到哪天、几天、之后第一次出门是什么量级）
           - 出行时段习惯（结合时段分布，说清是早起型还是夜间型）
           - 充电节奏（几次、跟里程的关系；没有充电记录就如实说本地还没攒到）
           - 前后半段的变化（前段与后段里程对比，是越跑越多还是回归日常）
           - 工作日与周末的差别
        3. 允许推测动机，但必须明确是推测，并且推测要挂在数据上，例如
           "10 月 4 日那 276 公里，看着不像通勤"。不许编造天气、地名、路况、同行的人。
        4. 不许写"请注意用车安全""继续保持良好的驾驶习惯""让每一次出行都更美好"这类空话。
           出现一句就算不合格。
        5. 电耗是"行驶电耗"（只含行驶用电，不含空调与待机），不要写成"综合电耗"，
           也不要拿它跟别的口径比较。

        输出格式（严格遵守，不要 Markdown，不要 emoji，不要分点符号）：
        第一行：标题，12 字以内，不加书名号
        空一行
        正文 5 段，每段 100~140 字，段间空一行。段落职责依次是：
          ① 这段时间的整体形状（用两三个数字定性，不要罗列全部指标）
          ② 跑得最多的那一天，写具体
          ③ 没动车的那段日子，写具体
          ④ 出行时段与充电节奏里的习惯
          ⑤ 前后半段的变化，或者与上一周期的对比（没有对比数据就写变化）
        空一行
        最后一行：一句不超过 25 字的收尾，可以是一个观察或一个愿望，不要口号
    """.trimIndent()

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
            .put("工作日里程_km", s.weekdayKm)
            .put("周末里程_km", s.weekendKm)
            .put("里程最多的星期", s.busiestWeekday)
            .put("最长连续用车_天", s.driveStreak)
            .put("那段连续用车起始日", s.driveStreakStart)
            .put("最长连续没动车_天", s.idleStreak)
            .put("那段闲置起始日", s.idleStreakStart)
            .put("前半段里程_km", s.firstHalfKm)
            .put("后半段里程_km", s.secondHalfKm)

        if (s.busiestStartHour >= 0) payload.put("通常几点出门", "${s.busiestStartHour} 点前后")
        if (s.busiestEndHour >= 0) payload.put("通常几点收车", "${s.busiestEndHour} 点前后")

        ReportStats.changePercent(s.totalKm, s.prevKm)?.let {
            payload.put("与上一周期相比_里程变化百分比", it)
        }
        s.prevChargeCount?.let { payload.put("上一周期充电次数", it) }

        // 出行时段分布：模型要靠它判断"早起型还是夜间型"
        payload.put("首次出行时段分布", JSONArray().apply {
            s.hourBuckets.forEach { (label, n) -> put(JSONObject().put(label, n)) }
        })

        payload.put("里程最高的几天", JSONArray().apply {
            s.topDays.forEach { (day, km, charges) ->
                put(JSONObject().put("日期", day).put("里程_km", km).put("当天充电次数", charges))
            }
        })

        // 按周聚合：年报时用它讲"哪几周最猛"
        if (s.weekly.isNotEmpty()) {
            payload.put("按周汇总", JSONArray().apply {
                s.weekly.forEach { (start, km, charges) ->
                    put(JSONObject().put("周起始", start).put("里程_km", km).put("充电次数", charges))
                }
            })
        }

        // 逐日里程原样给出：模型自己找规律比我们替它摘要更靠谱。
        // 用 "日期:里程" 的紧凑字符串，366 天也只占很小一段。
        payload.put("逐日里程", s.dailyKm.joinToString(",") { "${it.first}:${it.second}" })

        return payload
    }
}
