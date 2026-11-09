package com.haojay.leapmotor.data

import android.content.Context
import org.json.JSONObject

/**
 * 数据仓库：负责调用 API 并把 JSON 解析成模型。
 * 所有方法都是阻塞的，调用方需放在 IO 线程。
 */
class Repo(private val ctx: Context) {

    companion object {
        /** 信号盘点只做一次，避免每次轮询刷日志。 */
        private var audited = false
    }

    fun session(): LpSession? = SessionStore.load(ctx)

    private fun api(sess: LpSession) = LpApi(sess)

    /** 会话里没有 VIN 时就地补一次（登录时解析失败、或会话文件被裁剪过）。 */
    private fun resolveVin(sess: LpSession): String {
        val vin = AuthManager(ctx).firstVin(api(sess).getVehicleList())
        if (vin.isNullOrEmpty()) {
            error("未获取到车辆：请确认该账号已被授权共享车辆")
        }
        sess.vin = vin
        SessionStore.save(ctx, sess)
        return vin
    }

    // ---------------- 车况 ----------------

    fun fetchSnapshot(sess: LpSession): CarSnapshot {
        val vin = sess.vin ?: resolveVin(sess)
        val resp = api(sess).getCarState(vin)
        val data = resp.optJSONObject("data")
            ?: error(resp.optString("message").ifEmpty { "接口返回异常" })
        val sm = data.optJSONObject("signalMap") ?: error("缺少 signalMap")
        val m = mutableMapOf<String, Double>()
        sm.keys().forEach { k ->
            m[SignalIds.MAP[k] ?: "raw_$k"] = sm.optDouble(k, Double.NaN)
        }
        val st = m["signal_time"]?.toLong() ?: 0L
        // 一次性盘点：云端到底给了多少信号、我们没映射的有哪些（只打一次，别刷日志）
        if (!audited) {
            audited = true
            // JSONObject.keys() 是 Iterator，不是集合，得自己收一遍
            val raw = buildList { val it = sm.keys(); while (it.hasNext()) add(it.next()) }
            val unknown = raw.filter { SignalIds.MAP[it] == null }
            android.util.Log.i(
                "SignalAudit",
                "云端 " + raw.size + " 个信号，已映射 " + (raw.size - unknown.size) +
                    "，未映射 " + unknown.size + "：" + unknown.take(40)
            )
            // 未映射的那些把值也打出来，方便判断有没有价值
            unknown.forEach { k ->
                android.util.Log.i("SignalAudit", "  raw $k = " + sm.optDouble(k, Double.NaN))
            }
        }
        return CarSnapshot(m, st, data.optLong("collectTime"))
    }

    /**
     * 驻车照片。
     *
     * 只读接口，返回 `data.fileUrl`（OSS 签名地址）与 `data.uploadTime`。
     * 空字符串表示这辆车/这个时间没有照片——哨兵抓拍才有，不是每次驻车都有。
     */
    fun fetchParkingPhoto(sess: LpSession): ParkingPhoto? {
        val vin = sess.vin ?: resolveVin(sess)
        val resp = api(sess).carCall("v3/api/chassis/query", mapOf("vin" to vin))
        val data = resp.optJSONObject("data") ?: return null
        val url = data.optString("fileUrl").takeIf { it.startsWith("http") } ?: return null
        return ParkingPhoto(url, data.optLong("uploadTime"))
    }

    // ---------------- 趋势 ----------------

    fun fetchTrend(sess: LpSession, days: Int = 30): TrendData {
        val vin = sess.vin ?: resolveVin(sess)
        val a = api(sess)

        val mileageResp = a.getMileageRange(vin, days)
        val md = mileageResp.optJSONObject("data") ?: JSONObject()

        val detail = md.optJSONArray("detail")
        val daily = buildList {
            if (detail != null) {
                for (i in 0 until detail.length()) {
                    val o = detail.optJSONObject(i) ?: continue
                    add(
                        DailyPoint(
                            day = o.optString("day"),
                            mileage = o.optDouble("accumulatedMileage", 0.0),
                            energy = o.optDouble("accumulatedEnergyConsume", 0.0),
                        )
                    )
                }
            }
        }.sortedBy { it.day }

        // 六周能耗与排名（失败不致命）
        var weekly = emptyList<WeeklyEc>()
        var rank: String? = null
        var hundred: Double? = null
        runCatching {
            val er = a.getEnergyRank(vin).optJSONObject("data")
            er?.optJSONObject("rankResult")?.let {
                rank = it.optString("rank").ifEmpty { null }
                hundred = it.optDouble("hundredKmEC").takeIf { v -> !v.isNaN() }
            }
            val arr = er?.optJSONArray("weeklyEC")
            if (arr != null) {
                weekly = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            WeeklyEc(
                                start = o.optString("weekStart"),
                                end = o.optString("weekEnd"),
                                ec = o.optDouble("hundredKmEC", 0.0),
                            )
                        )
                    }
                }
            }
        }

        // 上周分项（失败不致命）
        var lastWeek: WeekBreakdown? = null
        runCatching {
            val lw = a.getLastWeekEC(vin).optJSONObject("data")
            if (lw != null) {
                lastWeek = WeekBreakdown(
                    driver = lw.optString("driverEC").toDoubleOrNull() ?: 0.0,
                    ac = lw.optString("acEC").toDoubleOrNull() ?: 0.0,
                    other = lw.optString("otherEC").toDoubleOrNull() ?: 0.0,
                )
            }
        }

        // 插枪期间电耗：独立接口，失败不影响主流程
        val plugEc = runCatching {
            fetchPlugEnergy(sess).optJSONObject("data")
                ?.optJSONObject("hundredKmEC")
                ?.optString("ec100km")?.toDoubleOrNull()
        }.getOrNull()

        return TrendData(
            totalMileage = md.optInt("totalmileage"),
            deliveryDays = md.optInt("deliveryDays"),
            totalEnergy = md.optDouble("totalEnergy", 0.0),
            daily = daily,
            weekly = weekly,
            lastWeek = lastWeek,
            rankPercent = rank,
            hundredKmEc = hundred,
            plugEc = plugEc,
        )
    }

    // ---------------- 车控 ----------------

    /** 读充电计划（上限 / 预约 / 周几 / 是否启用）。 */
    /** 插枪期间六周电耗。返回原始 JSON，结构由调用方解释（目前只做探测）。 */
    fun fetchPlugEnergy(sess: LpSession): org.json.JSONObject {
        val vin = sess.vin ?: resolveVin(sess)
        val a = api(sess)
        // 参数名不确定：先按 carvin（和 EP_LASTWEEK_EC 一致），失败再试 vin
        val first = runCatching { a.getPlugEnergy(vin) }
        first.exceptionOrNull()?.let {
            android.util.Log.i("PlugEnergy", "carvin 失败: " + it::class.java.simpleName + " / " + it.message)
        }
        first.getOrNull()?.let { return it }
        val second = runCatching { a.carCall(Constants.EP_PLUG_ENERGY, mapOf("vin" to vin)) }
        second.exceptionOrNull()?.let {
            android.util.Log.i("PlugEnergy", "vin 失败: " + it::class.java.simpleName + " / " + it.message)
        }
        return second.getOrThrow()
    }

    fun fetchChargePlan(sess: LpSession): org.json.JSONObject {
        val vin = sess.vin ?: resolveVin(sess)
        return api(sess).getChargePlan(vin)
    }

    /** 改充电上限（50~100），内部先读后写，避免覆盖预约设置。 */
    fun setChargeSoc(sess: LpSession, soc: Int): Pair<Boolean, String> {
        val vin = sess.vin ?: resolveVin(sess)
        return try {
            val r = api(sess).setChargeSoc(vin, soc)
            if (r.optInt("code", -1) == 0) true to "充电上限已设为 $soc%"
            else false to r.optString("message").ifEmpty { "设置失败" }
        } catch (e: Exception) {
            false to (e.message ?: "设置失败")
        }
    }

    /** 下发指令；返回 (成功, 提示语)。cmdId 480 走健康充电专用接口。 */
    fun sendCommand(sess: LpSession, cmdId: Int, state: Map<String, Any?>): Pair<Boolean, String> {
        val vin = sess.vin ?: run {
            CmdLog.record(ctx, cmdId, state, -1, "会话缺少 VIN", false, 0)
            return false to "会话缺少 VIN"
        }
        val started = System.currentTimeMillis()
        var code = -1
        var ok = false
        var msg = ""
        try {
            val r = if (cmdId == 480) {
                // 健康充电不是通用车控指令，用 480 会被受理但不执行
                api(sess).healthyCharging(vin, state["state"]?.toString() == "1")
            } else {
                api(sess).remoteControl(vin, cmdId, state)
            }
            code = r.optInt("code", -1)
            // 完整响应打进 logcat：失败时服务端会把真实原因塞在 data 里
            // （例如 "java.lang.Integer cannot be cast to java.lang.String"）
            android.util.Log.d("LPCMD", "cmd=$cmdId state=$state → ${r.toString().take(400)}")
            if (code == 0) {
                ok = true
                msg = "指令已下发"
            } else {
                val m = r.optString("message").ifEmpty { "失败" }
                msg = "$m（$code）"
            }
        } catch (e: Exception) {
            msg = e.message ?: "网络异常"
        }
        // 落盘留痕：车控是对真实车辆的动作，logcat 会被清空，不能作为凭据
        CmdLog.record(ctx, cmdId, state, code, msg, ok, System.currentTimeMillis() - started)
        return ok to msg
    }
}
