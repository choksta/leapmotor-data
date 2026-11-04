package com.haojay.leapmotor.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 会话持久化。
 *
 * **落盘已加密**：`session.bin` = AES-256-GCM（密钥在 Android Keystore，不出 TEE）。
 * 里面含 `car_token` / `hkdf_key_hex` / `refresh_token`，明文落盘等于把车控权限写进文件。
 *
 * 兼容旧版本：首次读取时会发现遗留的明文 `session.json`，自动重新加密成
 * `session.bin` 并删除明文，用户无感（不需要重新登录）。
 */
object SessionStore {

    private fun secretFile(ctx: Context) = File(ctx.filesDir, "session.bin")

    private fun legacyPlainFile(ctx: Context) = File(ctx.filesDir, "session.json")

    fun load(ctx: Context): LpSession? {
        // 1) 加密文件
        val bin = secretFile(ctx)
        if (bin.exists()) {
            val text = runCatching { SecureStore.decrypt(bin.readBytes()) }.getOrNull()
            if (text != null) return parse(text)
            // 密钥失效（换机 / 清数据 / 系统重置）→ 视为没有会话，走重新登录
            runCatching { bin.delete() }
            return null
        }

        // 2) 迁移：旧版明文文件
        val legacy = legacyPlainFile(ctx)
        if (legacy.exists()) {
            val text = runCatching { legacy.readText(Charsets.UTF_8) }.getOrNull()
            val session = text?.let { parse(it) }
            if (session != null) {
                save(ctx, session)                        // 写成加密文件
                runCatching { legacy.delete() }            // 删掉明文
                return session
            }
            runCatching { legacy.delete() }
        }
        return null
    }

    private fun parse(text: String): LpSession? = try {
        val o = JSONObject(text)
        LpSession(
            deviceId = o.optString("device_id"),
            phone = o.optString("phone").ifEmpty { null },
            token = o.optString("token").ifEmpty { null },
            refreshToken = o.optString("refresh_token").ifEmpty { null },
            accountId = o.optString("account_id").ifEmpty { o.optString("user_id").ifEmpty { null } },
            carToken = o.optString("car_token").ifEmpty { null },
            carRefreshToken = o.optString("car_refresh_token").ifEmpty { null },
            signR2 = o.optJSONObject("sign_param")?.optString("r2")?.ifEmpty { null },
            signR3 = o.optJSONObject("sign_param")?.optString("r3")?.ifEmpty { null },
            hkdfKey = o.optString("hkdf_key_hex").ifEmpty { null },
            vin = o.optString("car_vin").ifEmpty { null },
            carType = o.optString("car_type").ifEmpty { null },
            nickName = o.optString("nick_name").ifEmpty { null },
        )
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, s: LpSession) {
        val o = JSONObject().apply {
            put("device_id", s.deviceId)
            put("phone", s.phone ?: "")
            put("token", s.token ?: "")
            put("refresh_token", s.refreshToken ?: "")
            put("account_id", s.accountId ?: "")
            put("user_id", s.accountId ?: "")
            put("car_token", s.carToken ?: "")
            put("car_refresh_token", s.carRefreshToken ?: "")
            put("hkdf_key_hex", s.hkdfKey ?: "")
            put("car_vin", s.vin ?: "")
            put("car_type", s.carType ?: "")
            put("nick_name", s.nickName ?: "")
            put("sign_param", JSONObject().apply {
                put("r2", s.signR2 ?: "")
                put("r3", s.signR3 ?: "")
            })
        }
        runCatching { secretFile(ctx).writeBytes(SecureStore.encrypt(o.toString())) }
    }

    /** 退出登录：删除会话（deviceId 保留在 SettingsStore，避免下次被判新设备）。 */
    fun clear(ctx: Context) {
        runCatching { secretFile(ctx).delete() }
        runCatching { legacyPlainFile(ctx).delete() }
    }
}
