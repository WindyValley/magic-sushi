// 顶层 build.gradle.kts
// Magic Sushi — 声明所有子模块会用到的 plugin（apply false）
// 各模块的 build.gradle.kts 再用 plugins { } 块启用

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
