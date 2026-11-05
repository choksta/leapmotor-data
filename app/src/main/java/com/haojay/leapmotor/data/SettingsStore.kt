package com.haojay.leapmotor.data

import android.content.Context
import android.util.Base64

/**
 * 本地设置。
 *
 * **车控操作密码（PIN）加密存储**：SharedPreferences 里存的是
 * `enc:<base64(AES-256-GCM 密文)>`，密钥在 Android Keystore。
 * PIN 只有 4 位（1 万种组合），明文落盘等于直接可得；加密后再配合 Keystore
 * 绑定本机，拷走文件也解不开。
 *
 * `device_id` 不加密：它是设备标识、不是凭据，加密反而会让日志排障变麻烦。
 */
object SettingsStore {

    private const val FILE = "leapmotor_prefs"
    private const val KEY_PIN = "operate_pin"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CHECK_INTERVAL = "update_interval_h"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_MONITOR = "background_monitor"
    private const val KEY_MAP_WGS = "map_use_wgs"
    private const val ENC_PREFIX = "enc:"

    /** 默认每 6 小时查一次更新。 */
    const val DEFAULT_INTERVAL_H = 6L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPin(ctx: Context): String? {
        val raw = sp(ctx).getString(KEY_PIN, null)?.takeIf { it.isNotEmpty() } ?: return null

        // 已加密
        if (raw.startsWith(ENC_PREFIX)) {
            val blob = runCatching { Base64.decode(raw.removePrefix(ENC_PREFIX), Base64.NO_WRAP) }
                .getOrNull() ?: return null
            return SecureStore.decrypt(blob)?.takeIf { it.isNotEmpty() }
        }

        // 旧版明文 → 就地迁移成密文
        if (raw.all { it.isDigit() }) {
            setPin(ctx, raw)
            return raw
        }
        return null
    }

    /** 登录设备标识：必须长期固定，换新设备 ID 会触发风控、并使 refreshToken 失效。 */
    fun getDeviceId(ctx: Context): String? =
        sp(ctx).getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }

    fun setDeviceId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun setPin(ctx: Context, pin: String) {
        val blob = SecureStore.encrypt(pin)
        val encoded = ENC_PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
        sp(ctx).edit().putString(KEY_PIN, encoded).apply()
    }

    fun clearPin(ctx: Context) {
        sp(ctx).edit().remove(KEY_PIN).apply()
    }

    // ---------------- 更新检查 ----------------

    /** 检查间隔（小时）：6 / 72 / 168 三档。 */
    fun getIntervalHours(ctx: Context): Long =
        sp(ctx).getLong(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL_H).takeIf { it in 1..24L * 30 } ?: DEFAULT_INTERVAL_H

    fun setIntervalHours(ctx: Context, hours: Long) {
        sp(ctx).edit().putLong(KEY_CHECK_INTERVAL, hours).apply()
    }

    /** 上次成功检查的时间戳；0 表示从未查过。 */
    fun getLastCheckAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckAt(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_LAST_CHECK, at).apply()
    }

    // ---------------- 后台监控 ----------------

    /**
     * 是否在没插枪时也保持后台监控。
     *
     * 默认关。开了之后前台服务常驻，才能在你离车后提醒"车门没锁"；
     * 代价是一条常驻通知和少量耗电，所以这个决定交给用户，不默认替人做。
     */
    fun getMonitorOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_MONITOR, false)

    fun setMonitorOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_MONITOR, on).apply()
    }

    // ---------------- 地图坐标基准 ----------------

    /** 高德跳转是否改用 WGS-84。默认 false（车端信号本身就是 GCJ-02）。 */
    fun getMapUseWgs(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_MAP_WGS, false)

    fun setMapUseWgs(ctx: Context, wgs: Boolean) {
        sp(ctx).edit().putBoolean(KEY_MAP_WGS, wgs).apply()
    }
}
