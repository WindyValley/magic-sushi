// app/build.gradle.kts
// Magic Sushi — app 模块配置（Kotlin + Jetpack Compose）

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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
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






