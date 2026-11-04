# ---- 基础 ----
# 保留行号，崩溃栈才可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- OkHttp / Okio ----
# 这两个库大量用可选依赖（consrypt、bouncycastle、jsr305 等），
# 缺类只是告警，不影响运行，但 R8 默认会当错误终止构建。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---- Kotlin 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- org.json ----
# 服务端返回的字段全部字符串 key 动态读取，不涉及反射，无需 keep。
# 但 Android 平台实现内部有 native 方法，保留一下更稳。
-keep class org.json.** { *; }

# ---- 应用自身 ----
# 本项目的加解密走 JCA（javax.crypto），无反射、无序列化，不需要额外 keep。
# 若以后引入 @Serializable / 反射式 JSON，需在此补 keep 规则。
