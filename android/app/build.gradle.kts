plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dshgo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshgo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "4.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // CrashLog 记录版本号用
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 + 资源压缩：裁掉 Compose 运行时里没走到的分支，体积差一个数量级。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 个人自用：release 用 debug 密钥签名，装得上即可。
            // 要分发就换成自己的 keystore（见 README「签名」一节）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    // ---- 原生 UI：首页 / 设置 / 连接页全部用 Compose 自绘 ----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // material-icons-core 已够用（Add / Refresh / Settings / Search / Close / ArrowBack…）
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // 事件流走 WebSocket（Android 平台没有 java.net.http.WebSocket）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 扫码。选 ZXing 的嵌入式封装而不是 ML Kit：后者依赖 Google Play 服务，
    // 侧载安装 + 国内设备上常常不可用。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // JVM 单元测试：PageInject 拼出来的 JS 要能被外部校验
    testImplementation("junit:junit:4.13.2")


    // 注意：不要加 debugImplementation("androidx.compose.ui:ui-tooling")。
    // 它只服务 Android Studio 的预览，却会给 debug APK 塞进 ~18MB dex。
}
