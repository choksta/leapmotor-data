package com.haojay.leapmotor.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * 认证管理：短信登录、车端凭证交换、token 续期。
 *
 * 会话寿命（实测）：
 * - 账号 token 约 6 小时，可用 `refreshToken` 免短信续期（refreshToken 不轮换）
 * - 车端 JWT 约 2 小时，过期后需用账号 token 重新 `carLogin()` 交换
 * 所以「能不能长期用」取决于 refreshToken 是否还有效——它只在别处重新登录时失效。
 */
class AuthManager(private val ctx: Context) {

    /** 复用持久化的 deviceId：换设备 ID 更容易触发风控。 */
    fun deviceId(): String {
        SettingsStore.getDeviceId(ctx)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        SettingsStore.setDeviceId(ctx, id)
        return id
    }

    fun sendCode(phone: String): Result<Unit> = runCatching {
        val sess = LpSession(deviceId = deviceId())
        val r = LpApi(sess).requestSmsCode(phone)
        val code = r.optInt("code", -1)
        when (code) {
            200 -> Unit
            1023 -> error("账号风控冷却中（10 分钟起），请稍后再试，且不要连续重试")
            36 -> error("验证码发送太频繁，请稍等几分钟")
            else -> error(r.optString("message").ifEmpty { "发送失败（$code）" })
        }
    }

    /** 手机号 + 验证码登录，成功后自动交换车端 JWT 并落盘。 */
    fun login(phone: String, smsCode: String): Result<LpSession> = runCatching {
        val sess = LpSession(deviceId = deviceId(), phone = phone)
        val api = LpApi(sess)

        val r = api.login(phone, smsCode)
        val code = r.optInt("code", -1)
        if (code != 200 || sess.token.isNullOrEmpty()) {
            val msg = when (code) {
                1023 -> "账号风控冷却中，请 30 分钟后再试"
                4 -> "验证码错误"
                else -> r.optString("message").ifEmpty { "登录失败（$code）" }
            }
            error(msg)
        }

        val r2 = api.carLogin()
        if (sess.carToken.isNullOrEmpty() || sess.signR2.isNullOrEmpty()) {
            error("车端凭证交换失败：${r2.optString("message").ifEmpty { "未知原因" }}")
        }

        // 拉一次车辆列表，补齐 VIN / 车型（响应形状多样，需宽容解析）
        val vin = firstVin(api.getVehicleList())
        if (!vin.isNullOrEmpty()) {
            sess.vin = vin
            sess.carType = "B01"
        }

        SessionStore.save(ctx, sess)
        sess
    }

    /**
     * 从车辆列表响应里抽第一个 VIN。
     * 实测响应形状不一致：数组字段可能是 sharedcars / bindcars / cars / list / vehicleList，
     * VIN 字段可能是 vin / carVin / carvin。
     */
    fun firstVin(resp: JSONObject): String? {
        val data = resp.optJSONObject("data") ?: return null
        for (key in listOf("sharedcars", "bindcars", "cars", "list", "vehicleList")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vin = o.optString("vin")
                    .ifEmpty { o.optString("carVin") }
                    .ifEmpty { o.optString("carvin") }
                if (vin.isNotEmpty()) return vin
            }
        }
        return data.optString("vin").ifEmpty { null }
    }

    /** 清空会话（保留 deviceId，避免下次登录被判新设备）。 */
    fun logout() {
        SessionStore.clear(ctx)
    }

    // ---------------- 续期 ----------------

    /** 从 JWT 的 payload 读 exp（秒）；解析失败返回 0。 */
    fun jwtExpiry(token: String?): Long {
        if (token.isNullOrEmpty()) return 0
        val parts = token.split(".")
        if (parts.size < 2) return 0
        return runCatching {
            // 用宽容解码：JWT 段是 url-safe，但同样兼容标准 base64
            val payload = String(Crypto.base64UrlDecode(parts[1]), Charsets.UTF_8)
            JSONObject(payload).optLong("exp", 0L)
        }.getOrDefault(0L)
    }

    /**
     * 确保会话可用：车端 JWT 剩余不足 [skewSec] 秒时，
     * 先免短信续账号 token，再重新交换车端 JWT。
     *
     * @return 是否发生了续期
     */
    fun ensureFresh(session: LpSession, skewSec: Long = 600): Boolean {
        val exp = jwtExpiry(session.carToken)
        if (exp > 0 && exp - System.currentTimeMillis() / 1000 > skewSec) return false

        val api = LpApi(session)
        // 账号 token 到期前先续（免短信）；失败不致命，交换时会给出真实结论
        runCatching { api.refreshAccountToken() }
        val r = api.carLogin()
        val ok = !session.carToken.isNullOrEmpty() &&
            r.optInt("code", -1) == 0
        if (ok) SessionStore.save(ctx, session)
        return ok
    }
}
