package com.haojay.leapmotor.data

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 会话材料（与 Python 版 Session 对齐）。 */
data class LpSession(
    var deviceId: String = "",
    var phone: String? = null,
    var token: String? = null,              // 账号 token（约 6 小时）
    var refreshToken: String? = null,
    var accountId: String? = null,
    var carToken: String? = null,           // 车端 JWT（约 2 小时）
    var carRefreshToken: String? = null,
    var signR2: String? = null,
    var signR3: String? = null,
    var hkdfKey: String? = null,
    var vin: String? = null,
    var carType: String? = null,
    var nickName: String? = null,
    /** 车控操作密码（4 位 PIN），只在内存中；持久化由 SettingsStore 负责。 */
    var operatePwd: String? = null,
) {
    /** 取签名密钥，未派生则现算。 */
    fun sealKey(): String {
        hkdfKey?.takeIf { it.isNotEmpty() }?.let { return it }
        val ct = carToken ?: error("缺少 carToken")
        val r2 = signR2 ?: error("缺少 signParam.r2")
        val r3 = signR3 ?: error("缺少 signParam.r3")
        return Crypto.deriveHkdfKey(ct, r2, r3).also { hkdfKey = it }
    }
}

/**
 * 零跑中国区 API 客户端（只读部分）。
 *
 * 与 `api_client.py` 对齐的要点：
 * - 车况接口挂在 `/app/app-signal-service/signal/info/query`，**不在** `/carownerservice` 下
 * - POST 表单 `vin=xxx`，字段同样参与签名
 * - 车辆列表走 global 网关，额外带 `userId` 头
 */
class LpApi(private val session: LpSession, private val timeoutMs: Int = 20000) {

    // ---------------- 请求头 ----------------

    private fun signedHeaders(): MutableMap<String, String> = mutableMapOf(
        "acceptLanguage" to Constants.DEFAULT_LANGUAGE,
        "channel" to Constants.APP_CHANNEL,
        "deviceType" to Constants.APP_DEVICE_TYPE,
        "source" to Constants.APP_SOURCE,
        "timestamp" to System.currentTimeMillis().toString(),
        "version" to Constants.APP_VERSION,
        "deviceId" to session.deviceId,
    )

    /**
     * 发送但不参与签名的头。
     * 注意 `carvin` / `cartype` / `userId` 是必须带的——缺了服务端会走不同分支。
     */
    private fun extraHeaders(): MutableMap<String, String> {
        val h = mutableMapOf(
            "x-region" to Constants.DEFAULT_REGION,
            "x-subversion" to Constants.APP_SUBVERSION,
            "x-api-signature-version" to Constants.X_API_SIGNATURE_VERSION,
        )
        session.carToken?.let { h["token"] = it }
        session.accountId?.let { h["userId"] = it }
        session.vin?.let { h["carvin"] = it }
        session.carType?.let { h["cartype"] = it }
        return h
    }

    /** 构造签名头：业务参数 ∪ 7 个签名头，按 key 排序拼接后 HMAC-SHA256。 */
    fun buildHeaders(payload: Map<String, Any?>, accountScope: Boolean = false): MutableMap<String, String> {
        val h = signedHeaders()
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(payload)
        merged.putAll(h)
        val raw = Crypto.sortAndConcat(merged)
        val sign = Crypto.hmacSha256Hex(Crypto.hexToBytes(session.sealKey()), raw)
        h.putAll(extraHeaders())
        if (accountScope) session.accountId?.let { h["userId"] = it }
        h["sign"] = sign
        h["digest"] = sign
        // ⚠️ 不要把 raw / sign / headers 打进日志：
        // headers 里有完整 JWT，raw 是签名原文（车控指令时含加密后的操作密码）。
        // logcat 在有 root 或抓 bug report 时是可读的，等于把凭证写进明文日志。
        // 只记字段数量，不记内容。
        android.util.Log.d("LPApi", "sign ok (fields=" + merged.size + ")")
        return h
    }

    private fun accountHeaders(): MutableMap<String, String> = mutableMapOf(
        "APPPlatform" to "Android",
        "APPVersion" to Constants.APP_VERSION,
        "C-VERSIONS" to "APP",
        "Content-Type" to "application/json;charset=UTF-8",
        "APPImei" to session.deviceId,
    )

    // ---------------- HTTP ----------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * 用 OkHttp 而非 HttpURLConnection：后者会规范化 header 名（驼峰被改写），
     * 导致服务端按 `acceptLanguage`/`deviceId` 原名取不到值，签名校验失败。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .build()

    private fun http(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: Map<String, Any?>? = null,
        form: Boolean = false,
    ): JSONObject {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.addHeader(k, v) }

        val requestBody: RequestBody? = when {
            body == null && method == "GET" -> null
            // 登录接口是 POST 但参数全在 query string、请求体为空。
            // OkHttp 不允许 method("POST", null)，必须给一个空的 RequestBody。
            body == null -> ByteArray(0).toRequestBody(null)
            form -> FormBody.Builder().apply {
                body.forEach { (k, v) -> add(k, Crypto.pyStr(v)) }
            }.build()
            else -> {
                val obj = JSONObject()
                body.forEach { (k, v) -> obj.put(k, v) }
                obj.toString().toRequestBody("application/json;charset=UTF-8".toMediaType())
            }
        }
        rb.method(method, requestBody)

        client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("_raw", text).put("_http", resp.code)
            }
        }
    }

    // ---------------- 账号侧 ----------------

    /** 发送短信验证码。手机号必须 RSA 加密，否则报 1019。 */
    fun requestSmsCode(phone: String): JSONObject {
        val url = "${Constants.ACCOUNT_BASE}/${Constants.EP_SMS_CODE}?phoneNo=${Crypto.rsaEncryptB64Url(phone)}"
        return http("GET", url, accountHeaders())
    }

    /** 手机号 + 验证码登录。参数在 query string，验证码是明文。 */
    fun login(phone: String, smsCode: String): JSONObject {
        val query = listOf(
            "phoneNoCiphertext" to Crypto.rsaEncryptB64Url(phone),
            "smsCode" to smsCode,
            "os" to "android",
            "pageUrl" to "精选",
            "deviceID" to session.deviceId,
        ).joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val url = "${Constants.ACCOUNT_BASE}/${Constants.EP_LOGIN}?$query"
        val resp = http("POST", url, accountHeaders())

        if (resp.optInt("code") == 200) {
            session.phone = phone
            val data = resp.optJSONObject("data")
            var vo = JSONObject()
            for (key in listOf("appLoginVO", "appOneLoginVO", "appOtherLoginVO")) {
                data?.optJSONObject(key)?.let { vo = it }
                if (vo.has("token")) break
            }
            if (!vo.has("token") && data?.has("token") == true) vo = data
            vo.optString("token").takeIf { it.isNotEmpty() }?.let { session.token = it }
            vo.optString("refreshToken").takeIf { it.isNotEmpty() }?.let { session.refreshToken = it }
            val uid = vo.optString("accountId").ifEmpty { vo.optString("userId") }
            if (uid.isNotEmpty()) session.accountId = uid
        }
        return resp
    }

    /**
     * 账号 token 免短信续期。
     *
     * 要点（实测，少一项就失败）：
     * - `refreshToken` 走**请求头** `XFX-CDN-CROSS-REFRESH-NODE`，不是 query；
     *   缺这个头服务端直接 500。
     * - 签名串 = {accountId, accountNumber, deviceID, nonce, refreshtoken, timespan}
     *   六项按 key 排序拼接取 MD5[8:24]，其中 `refreshtoken` **只参与签名、不发送**。
     * - `deviceID` 必须与登录时一致，否则 `code=45 refreshtoken失效`。
     * - refreshToken 不轮换，只要它自身没过期就能一直续（6 小时 → 长期免登录）。
     */
    fun refreshAccountToken(): JSONObject {
        val refresh = session.refreshToken ?: error("缺少 refreshToken")
        val accId = session.accountId ?: error("缺少 accountId")
        val phone = session.phone ?: error("缺少手机号")

        val params = linkedMapOf<String, Any?>(
            "timespan" to System.currentTimeMillis().toString(),
            "nonce" to (10000..9999999).random().toString(),
            "deviceID" to session.deviceId,
            "refreshtoken" to refresh,
            "accountId" to accId,
            "accountNumber" to Crypto.rsaEncryptB64Url(phone),
        )
        val raw = params.keys.sorted().joinToString("") { Crypto.pyStr(params[it]) }
        val sign = Crypto.md5Mid16(raw)
        params.remove("refreshtoken")

        val query = params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(Crypto.pyStr(it.value))}"
        } + "&signStr=$sign"
        val url = "${Constants.ACCOUNT_BASE}/${Constants.EP_REFRESH_ACCOUNT}?$query"

        val headers = accountHeaders().apply {
            remove("Content-Type")
            put("XFX-CDN-CROSS-REFRESH-NODE", refresh)
            put("XFX-CDN-VRS", "v4")
            put("C-VERSIONS", "APP")
        }
        session.token?.let { headers["XFX-CDN-CROSS-NODE"] = it }

        val resp = http("GET", url, headers)
        if (resp.optInt("code") in listOf(0, 200)) {
            resp.optJSONObject("data")?.optString("token")
                ?.takeIf { it.isNotEmpty() }
                ?.let { session.token = it }
        }
        return resp
    }

    /** 用账号 token 换车端 JWT + 签名材料。 */
    fun carLogin(): JSONObject {
        val accountId = session.accountId ?: error("缺少 accountId，请先登录")
        val token = session.token ?: error("缺少账号 token，请先登录")
        val body = mapOf(
            "identifier" to accountId,
            "security" to token,
            "identifierType" to "1",
        )
        val url = "${Constants.GLOBAL_BASE}/base/base-user/account/v1/login"
        val resp = http("POST", url, exchangeHeaders(body, upper = true), body = body)

        val data = resp.optJSONObject("data")
        if (data != null) {
            data.optString("accessToken").takeIf { it.isNotEmpty() }?.let {
                session.carToken = it
                session.hkdfKey = null
            }
            data.optString("refreshToken").takeIf { it.isNotEmpty() }?.let { session.carRefreshToken = it }
            data.optJSONObject("signParam")?.let { sp ->
                sp.optString("r2").takeIf { it.isNotEmpty() }?.let { session.signR2 = it }
                sp.optString("r3").takeIf { it.isNotEmpty() }?.let { session.signR3 = it }
            }
            data.optString("accountId").takeIf { it.isNotEmpty() }?.let { session.accountId = it }
        }
        return resp
    }

    /** 交换接口的签名是 SHA256（此时还没有 hkdf 密钥），且 body 三个字段参与。 */
    private fun exchangeHeaders(body: Map<String, Any?>, upper: Boolean): MutableMap<String, String> {
        val h = signedHeaders()
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(body)
        merged.putAll(h)
        val raw = Crypto.sortAndConcat(merged)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var sig = Crypto.toHex(md.digest(raw.toByteArray(Charsets.UTF_8)))
        if (upper) sig = sig.uppercase()
        h.putAll(extraHeaders())
        h.remove("token")
        h["sign"] = sig
        h["digest"] = sig
        return h
    }

    // ---------------- 车端侧 ----------------

    /** 车辆列表（走 global 网关，带 userId 头）。 */
    fun getVehicleList(): JSONObject {
        val url = "${Constants.GLOBAL_BASE}${Constants.GLOBAL_PREFIX}v1/vehicle/list"
        return http("GET", url, buildHeaders(emptyMap(), accountScope = true))
    }

    /** 一帧完整车况。 */
    fun getCarState(vin: String): JSONObject {
        val body = mapOf("vin" to vin)
        val url = "${Constants.BASE_URL}${Constants.EP_SIGNAL_QUERY}"
        return http("POST", url, buildHeaders(body), body = body, form = true)
    }

    /** 通用车控服务 GET（里程/能耗/配置都走这里）。 */
    fun carCall(path: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        var url = "${Constants.BASE_URL}${Constants.API_PREFIX}${path.trimStart('/')}"
        if (params.isNotEmpty()) {
            url += if (url.contains('?')) "&" else "?"
            url += params.entries.joinToString("&") { "${enc(it.key)}=${enc(Crypto.pyStr(it.value))}" }
        }
        return http("GET", url, buildHeaders(params))
    }

    // ---------------- 车控 ----------------

    /**
     * 下发远程控制指令。
     *
     * 表单必填 `cmdid` / `state` / `carvin` / `nonce`；`state` 是指令内容对象，
     * 序列化成紧凑 JSON（分隔符不带空格）后才签名与提交。
     *
     * 各指令的 state 结构（实测自 App，非臆测）：
     * - 110 车门: {"value":"lock"|"unlock"}
     * - 120 寻车: {"value":"true"}
     * - 130 后备箱: {"value":"true"|"false"}
     * - 160 电池预热: {"value":"ptcon"|"ptcoff"}
     * - 193 充电: {"value":"start"|"stop"}
     * - 230 车窗: {"value":"2"|"0"}
     * - 240 遮阳帘: {"value":"10"|"0"}
     */
    fun remoteControl(
        vin: String,
        cmdId: Int,
        state: Map<String, Any?>,
        extra: Map<String, Any?> = emptyMap(),
    ): JSONObject {
        val stateObj = JSONObject()
        state.forEach { (k, v) -> stateObj.put(k, v) }
        val stateJson = stateObj.toString()
        val body = linkedMapOf<String, Any?>(
            "cmdid" to cmdId.toString(),
            "state" to stateJson,
            "carvin" to vin,
            "nonce" to (1000000..9999999).random().toString(),
        )
        body.putAll(extra)
        // 操作密码：服务端要求非空，且必须用「车端 JWT」派生 key/iv（aes_jwt 模式）。
        // 缺它时服务端统一回「请求参数为空或不合法」。
        session.operatePwd?.takeIf { it.isNotEmpty() }?.let { pin ->
            body["oppwd"] = Crypto.encryptOperatePwd(pin, session.carToken)
        }
        val url = "${Constants.BASE_URL}${Constants.API_PREFIX}${Constants.EP_REMOTE_CTL}"
        return http("POST", url, buildHeaders(body), body = body, form = true)
    }

    /**
     * 健康充电开关。
     *
     * ⚠️ 必须走**专用接口**，不是 cmdId 480：
     * `POST /carownerservice/v3/api/healthyCharging/control`
     * 表单 `carvin` + `state`(0/1) + `oppwd`。用 480 走通用车控服务端会受理但车端不执行。
     */
    fun healthyCharging(vin: String, on: Boolean): JSONObject {
        val body = linkedMapOf<String, Any?>(
            "carvin" to vin,
            "state" to if (on) "1" else "0",
        )
        session.operatePwd?.takeIf { it.isNotEmpty() }?.let { pin ->
            body["oppwd"] = Crypto.encryptOperatePwd(pin, session.carToken)
        }
        val url = "${Constants.BASE_URL}${Constants.API_PREFIX}v3/api/healthyCharging/control"
        return http("POST", url, buildHeaders(body), body = body, form = true)
    }

    /** 读取当前充电计划（充电上限 / 预约时段 / 周几）。 */
    fun getChargePlan(vin: String): JSONObject {
        val cfg = carCall("v3/api/vehicleinfo/commonConfig", mapOf("vin" to vin))
            .optJSONObject("data")?.optJSONObject("config")
        return cfg?.optJSONObject("3") ?: JSONObject()
    }

    /**
     * 修改充电上限（50~100）。
     *
     * ⚠️ 必须先读当前计划再整体回写：服务端是**整段覆盖**，
     * 只发 chargesoc 会把预约时段、周几、预约开关全部清掉。
     */
    fun setChargeSoc(vin: String, soc: Int): JSONObject {
        val cur = getChargePlan(vin)
        if (cur.length() == 0) error("读不到当前充电计划，未下发以免覆盖预约设置")
        return remoteControl(
            vin, 190,
            linkedMapOf(
                "chargeEnable" to cur.optInt("isEnable", 0),
                "chargesoc" to soc.coerceIn(50, 100),
                "circulation" to cur.optInt("circulation", 0),
                "cycles" to cur.optString("cycles", "1,1,1,1,1,1,1"),
                "endtime" to cur.optString("endTime", "08:00"),
                "recharge" to cur.optInt("recharge", 0),
                "starttime" to cur.optString("beginTime", "00:00"),
            )
        )
    }

    /** 查询指令执行结果（车控下发后需要按 msgID 轮询回执）。 */    fun queryCommand(msgId: String): JSONObject {
        val params = mapOf("msgID" to msgId)
        var url = "${Constants.BASE_URL}${Constants.API_PREFIX}${Constants.EP_REMOTE_CTL_QUERY}"
        url += "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return http("GET", url, buildHeaders(params))
    }

    // ---------------- 趋势数据 ----------------

    /** 近 N 天逐日里程 + 能耗。 */
    fun getMileageRange(vin: String, days: Int): JSONObject {
        val end = System.currentTimeMillis()
        val begin = end - days.toLong() * 86400_000L
        return carCall(
            Constants.EP_MILEAGE_DETAIL,
            linkedMapOf("vin" to vin, "begintime" to begin, "endtime" to end)
        )
    }

    /** 总里程 + 交付天数。 */
    fun getMileageDetail(vin: String): JSONObject =
        carCall(Constants.EP_MILEAGE_DETAIL, mapOf("vin" to vin))

    /** 近六周百公里能耗 + 排名。 */
    fun getEnergyRank(vin: String): JSONObject =
        carCall(Constants.EP_ENERGY_RANK, mapOf("carvin" to vin))

    /** 上周能耗拆分（驱动/空调/其它）。 */
    fun getLastWeekEC(vin: String): JSONObject =
        carCall(Constants.EP_LASTWEEK_EC, mapOf("carvin" to vin))

    /**
     * 插枪期间六周百公里电耗。
     *
     * 和 [getEnergyRank] 的差别是：只统计插枪（充电）时段，用来区分"充电损耗"与"行驶电耗"。
     * 这个接口一直存在但从未被调用过，先用探测确认结构。
     */
    fun getPlugEnergy(vin: String): JSONObject =
        carCall(Constants.EP_PLUG_ENERGY, mapOf("carvin" to vin))
}
