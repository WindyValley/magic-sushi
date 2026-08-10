// app/build.gradle.kts
// Magic Sushi — app 模块配置（Kotlin + Jetpack Compose）

// ⚠️ Kotlin DSL 里用 java.util.Properties 必须显式 import。
// 写成全限定名 `java.util.Properties()` 会报 "Unresolved reference 'util'"
// —— Gradle 的脚本编译器不解析内联的全限定 java 包名。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.windyvalley.magicsushi"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.windyvalley.magicsushi"
        minSdk = 26
        targetSdk = 34
        // 版本号遵循 SemVer（https://semver.org/lang/zh-CN/）。
        //
        // versionName = MAJOR.MINOR.PATCH
        //   MAJOR  破坏性变更 / 存档不兼容。1 表示功能完整、格式已定，
        //          之后改存档结构要考虑迁移。
        //   MINOR  新增功能（向后兼容）
        //   PATCH  仅修 bug
        //
        // versionCode 是 Android 用来判断"哪个包更新"的整数，与 SemVer 无关，
        // 只需**单调递增**。这里按发布次数计数：
        //   1 → v0.1.0    2 → v0.1.1    3 → v0.2.0    4 → v0.2.1
        //   5 → v1.0.0（本次）
        //
        // ⚠️ 改这里就够了：设置页的版本号走 BuildConfig.VERSION_NAME
        // （MainActivity 传给 SettingsScreen），不存在第二处需要同步的常量。
        //
        // v1.0.0 为什么是 MAJOR：这是第一个「达到发布标准」的版本，含义是
        // 结束 0.x 的初始开发阶段 —— 0.x 期间不保证存档格式与公开 API 稳定，
        // 1.0 之后要保证。功能上 v0.2.1 → v1.0.0 只修了 4 个渲染层 bug
        // （下落动画的跳动、回弹、两类 tile 的首帧闪），没加新功能，
        // 但 SemVer 的 1.0.0 表达的是**承诺**而不是功能量：
        // 见 semver.org 第 5 条 —— "1.0.0 版本定义了公共 API"。
        //
        // v0.2.1 为什么是 PATCH 而不是 MINOR：只换了图标资源（全套 mipmap
        // 密度 + 首次补上自适应图标），玩家能做的事一件没变。自适应图标虽然
        // 是"新增能力"，但它修的是旧图标在圆形遮罩下缺角、以及小字号渲染
        // 发糊的问题 —— 性质是修缺陷，不是加功能。
        //
        // v0.2.0 当时为什么是 MINOR：v0.1.1 之后新增了设置页面（静音开关
        // 首次有 UI 入口、清空历史记录、关于），是向后兼容的新功能。
        versionCode = 5
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release 签名配置。
    //
    // 密码和 keystore 路径来自 android-app/keystore.properties，该文件与
    // *.jks 一起被 .gitignore 挡住（已用 `git check-ignore` 验证），**不进仓库**。
    //
    // 文件不存在时 signingConfigs 留空，release 构建会产出 unsigned APK ——
    // 这样 CI / 新克隆的仓库仍能跑通 assembleRelease，不会因为缺密钥而失败。
    // 想出可安装的包就得自己建 keystore，见 keystore.properties.example。
    signingConfigs {
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            val keystoreProps = Properties().apply {
                keystorePropsFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 两种签名方案都开：v1 (JAR) 兼容 API < 24，v2 (APK Signature
                // Scheme) 是 API 24+ 的标准，验证更快且防篡改更强。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 有 keystore 就签名，没有就留 null（产出 unsigned APK）。
            signingConfig = signingConfigs.findByName("release")

            // ⚠️ 保持关闭。开启 R8 需要先验证混淆规则对 Compose + kotlinx
            // 序列化不误伤，那是独立的一轮验证工作，不适合和发布挤在一起。
            // 现在 APK 约 8MB，体积不是瓶颈。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 游戏引擎（纯 Kotlin JVM module，零 Android 依赖）。
    // api 而非 implementation：GameState / Board / SushiType 这些类型
    // 出现在 ui 与 viewmodel 的公开签名里，消费方需要能看到它们。
    api(project(":engine"))

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // DataStore —— 历史记录持久化（FIX_PLAN D8）。
    //
    // 为什么不继续用 SharedPreferences：PrefsRepository 在构造函数里
    // 调 getSharedPreferences()，那是**主线程同步读盘**。加历史记录后
    // 数据量从 2 个标量涨到 50 条记录，同步 IO 的代价不再可忽略。
    // DataStore 的读写都是 suspend/Flow，天然在 IO 线程。
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // 系统启动窗口延长（FIX_PLAN D8）。
    //
    // 冷启动要同步预热一次设置（最高分/静音），这段 IO 期间主线程被占住，
    // 自绘的 Compose 启动页同样画不出来 —— composition 也在主线程。
    // 只有系统启动窗口能盖住：它由 WindowManager 在进程起来前就绘制完成。
    // setKeepOnScreenCondition 把它留到数据就绪为止。
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Jetpack Compose (BOM)
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Compose Animation (animateColorAsState, AnimatedVisibility 等)
    implementation("androidx.compose.animation:animation")

    // Activity & Lifecycle Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    // 协程测试：runTest / runCurrent / TestScope（GameEventTest 需要）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}






