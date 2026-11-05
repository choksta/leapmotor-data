plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.haojay.leapmotor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haojay.leapmotor"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // R8 必须开：material-icons-extended 带进来上千个图标，
            // 不裁剪的话 dex 有 56MB（APK 体积的大头）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人自用：复用 debug 签名，避免额外维护 keystore。
            // 若要对外分发，换成自己的 signingConfig 并妥善保管。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.ui:ui-graphics:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    implementation("androidx.compose.material3:material3:1.5.0-alpha06")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
