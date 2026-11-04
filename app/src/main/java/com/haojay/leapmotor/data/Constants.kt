package com.haojay.leapmotor.data

/** 零跑中国区协议常量（实测自 App 1.23.04 与会话抓包）。 */
object Constants {
    const val BASE_URL = "https://appgateway.leapmotor.com"
    const val API_PREFIX = "/carownerservice/"

    const val ACCOUNT_BASE = "https://appuser.leapmotor.cn/app-user"

    const val GLOBAL_BASE = "https://app-gw-global-master.leapmotor.com"
    const val GLOBAL_PREFIX = "/app/app-global-service/"

    const val APP_SOURCE = "leapmotor"
    const val APP_CHANNEL = "1"
    const val APP_DEVICE_TYPE = "android"
    const val APP_VERSION = "1.22.98"
    const val APP_SUBVERSION = "3.21.3-2"
    const val X_API_SIGNATURE_VERSION = "2.0"
    const val DEFAULT_LANGUAGE = "zh-CN"
    const val DEFAULT_REGION = "CN"

    const val EP_SMS_CODE = "applogin/compliance/sendmessagecode"
    const val EP_LOGIN = "applogin/check_login_with_phone"
    const val EP_REFRESH_ACCOUNT = "appuseroperate/getnewtoken"

    const val EP_MILEAGE_DETAIL = "v3/api/drivingrecord/mileage/energy/detail"
    const val EP_ENERGY_RANK = "v3/api/drivingrecord/getLastNweeks100kmECAndRank"
    const val EP_PLUG_ENERGY = "v3/api/drivingrecord/getPlugInLastNweeks100kmEC"
    const val EP_LASTWEEK_EC = "v3/api/drivingrecord/getLastweekEC"
    const val EP_SIGNAL_QUERY = "/app/app-signal-service/signal/info/query"
    const val EP_REMOTE_CTL = "v3/api/appremotectl"
    const val EP_REMOTE_CTL_QUERY = "v3/api/appremotectl/query"

    /** 登录手机号加密用的 RSA 公钥（X.509/DER, base64）。 */
    const val RSA_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDHUIQKhkwNqJFTZPe98mC1lmpbY9r/+7PEWZg8ebqYXT3sumKRaQ0zcoTx42x0iybmCRXy4CcZrgGAbwKzwqwNw0rFquJ6c7mgQA6k3lZU3p96qBlzK7DSkoFR6mO9pjcd2hlJ8wH+IwI5b8IWWZhwVN/4cM7npG0S0zeRn3soEwIDAQAB"
}
