# T-ANDROID-002: Gradle 配置（Kotlin/Compose/SDK）

**状态：** ✅ done
**优先级：** P0
**预估：** 3h
**Owner：** main agent
**依赖：** T-ANDROID-001
**可并行：** -

## 目标

配置 Gradle 构建脚本：Kotlin、Jetpack Compose、依赖库、SDK 版本。

## 来源

- 设计：02-design.md §5 / 02-tech-stack.md
- 需求：NFR-2.1, NFR-2.2

## 验收标准

- [ ] Kotlin 1.9+
- [ ] Compose BOM 2024.02+
- [ ] minSdk 26, targetSdk 34, compileSdk 34
- [ ] 依赖：Compose、ViewModel、StateFlow、Lifecycle、Activity-Compose
- [ ] 测试依赖：JUnit 5、kotest
- [ ] `./gradlew build` 成功

## 技术要点

- `app/build.gradle.kts`：
  ```kotlin
  plugins {
      id("com.android.application")
      id("org.jetbrains.kotlin.android")
  }
  android {
      compileSdk = 34
      defaultConfig { minSdk = 26; targetSdk = 34 }
      buildFeatures { compose = true }
      composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
  }
  ```

## 产出物

- `android-app/build.gradle.kts`
- `android-app/app/build.gradle.kts`
- `android-app/settings.gradle.kts`
- `android-app/gradle.properties`

## 备注

- 版本号要锁，避免 SDK 漂移（R-001 类似风险）