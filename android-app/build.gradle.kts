// 顶层 build.gradle.kts
// Magic Sushi — 声明所有子模块会用到的 plugin（apply false）
// 各模块的 build.gradle.kts 再用 plugins { } 块启用

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // 引擎 module 用纯 JVM Kotlin —— 版本与 kotlin.android 保持一致，
    // 否则两个 module 的 stdlib 会打架。
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}
