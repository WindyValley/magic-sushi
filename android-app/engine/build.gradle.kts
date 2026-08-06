// engine/build.gradle.kts
// Magic Sushi 游戏引擎 —— 纯 Kotlin JVM library，零 Android 依赖。
//
// 为什么是 `kotlin("jvm")` 而不是 `com.android.library`：
//   Android library module 会把 android.jar 放进 compile classpath，
//   `import android.util.Log` 照样能编译通过 —— 那样"零 Android 依赖"
//   仍然只是自律。用纯 JVM module，Android API 根本不在 classpath 上，
//   写了就编译不过。约定从此由编译器保证，而不是靠人记得。
//
// 副作用（正面的）：引擎可被服务端、CLI、桌面端、KMP 目标直接复用。

plugins {
    kotlin("jvm")
}

// 不用 jvmToolchain(17)：那会要求机器上存在 JDK 17 精确匹配，
// 本机只有 JDK 21/25，会直接失败（且无法自动下载）。
// 只约束**字节码目标版本**为 17，与 app module 的 jvmTarget 保持一致 ——
// Android 侧需要 17 字节码，用更高版本 JDK 编译出 17 目标完全合法。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 唯一依赖：协程。CascadeAnimator 的时序编排需要 delay()，
    // GameEvent 需要 SharedFlow。两者都是纯 Kotlin，不含 Android。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    // runTest / TestScope / currentTime —— CascadeAnimatorTest 的虚拟时钟断言
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test {
    useJUnit()
}
