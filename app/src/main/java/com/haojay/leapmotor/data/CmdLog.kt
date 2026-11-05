package com.haojay.leapmotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车控指令账本。
 *
 * 存在的理由只有一个：**事后可回溯**。车控是对真实车辆的动作，用户有权在任何时刻查清
 * 「这个 App 到底发过什么指令、什么时候发的、服务端怎么回的」。
 *
 * 为什么不用 logcat：它会被清空、会被后续日志滚动覆盖，不能当凭据。
 * 所以这里落盘到 `filesDir/command_log.json`，最新在前，最多 60 条。
 * - App 内：控制页底部可见
 * - App 外：`adb shell su -c 'cat /data/data/com.haojay.leapmotor/files/command_log.json'`
 *
 * 只记录**指令本身**，不记凭证。操作密码一类的键在落盘前会被过滤掉。
 */
object CmdLog {
    private const val FILE = "command_log.json"
    private const val MAX = 60

    data class Entry(
        val at: Long,
        val cmdId: Int,
        val label: String,
        val params: String,
        val code: Int,
        val message: String,
        val ok: Boolean,
        val elapsedMs: Long,
    )

    /** cmdId → 中文名，未知的显示成「指令 123」。 */
    fun label(cmdId: Int): String = when (cmdId) {
        100 -> "点熄火"
        110 -> "车门锁"
        120 -> "寻车鸣笛"
        130 -> "后备箱"
        160 -> "电池预热"
        170 -> "空调"
        193 -> "充电启停"
        230 -> "车窗"
        240 -> "遮阳帘"
        301 -> "座椅加热"
        320 -> "方向盘加热"
        370 -> "座椅通风"
        400 -> "哨兵模式"
        440 -> "后视镜加热"
        480 -> "健康充电"
        else -> "指令 $cmdId"
    }

    /** 参数脱敏：任何含 pwd / password / pin 的键都不落盘。 */
    private fun safeParams(state: Map<String, Any?>): String =
        state.entries
            .filter { (k, _) ->
                val lk = k.lowercase()
                !lk.contains("pwd") && !lk.contains("password") && !lk.contains("pin")
            }
            .joinToString(", ") { (k, v) -> "$k=$v" }

    fun record(
        ctx: Context,
        cmdId: Int,
        state: Map<String, Any?>,
        code: Int,
        message: String,
        ok: Boolean,
        elapsedMs: Long,
    ) {
        runCatching {
            val entry = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("cmdId", cmdId)
                put("label", label(cmdId))
                put("params", safeParams(state))
                put("code", code)
                put("message", message)
                put("ok", ok)
                put("elapsedMs", elapsedMs)
            }
            val old = readArray(ctx)
            val out = JSONArray()
            out.put(entry)
            for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
            file(ctx).writeText(out.toString(), Charsets.UTF_8)
        }
    }

    fun recent(ctx: Context, limit: Int = 10): List<Entry> {
        val arr = readArray(ctx)
        val n = minOf(arr.length(), limit)
        return (0 until n).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                at = o.optLong("at"),
                cmdId = o.optInt("cmdId"),
                label = o.optString("label"),
                params = o.optString("params"),
                code = o.optInt("code"),
                message = o.optString("message"),
                ok = o.optBoolean("ok"),
                elapsedMs = o.optLong("elapsedMs"),
            )
        }
    }

    fun timeText(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))

    fun path(ctx: Context): String = file(ctx).absolutePath

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun readArray(ctx: Context): JSONArray = runCatching {
        val f = file(ctx)
        if (!f.exists()) JSONArray() else JSONArray(f.readText(Charsets.UTF_8))
    }.getOrDefault(JSONArray())
}
