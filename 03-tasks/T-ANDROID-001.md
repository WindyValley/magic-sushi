# T-ANDROID-001: Android Studio 工程脚手架

**状态：** ✅ done
**优先级：** P0
**预估：** 2h
**Owner：** main agent
**依赖：** -
**可并行：** T-CORE-008, T-RES-001, T-RES-002

## 目标

创建 `android-app/` Android Studio 工程结构。

## 来源

- 设计：02-design.md §5（Android 工程结构）
- 需求：NFR-2.1 (API 26+) / NFR-2.2 (API 34)

## 验收标准

- [ ] `android-app/` 目录结构与 02-design.md §5 一致
- [ ] 包名：`top.windyvalley.magicsushi`
- [ ] `settings.gradle.kts` 配置正确
- [ ] `build.gradle.kts` 顶层文件
- [ ] `app/build.gradle.kts` 模块文件
- [ ] `gradle/wrapper/` 配置 wrapper
- [ ] IDE 能打开工程

## 技术要点

- 用 `git init` 初始化
- 包结构：`java/top/windyvalley/magicsushi/`
- 不实际下载 SDK（环境已经准备好）

## 产出物

- `android-app/` 完整目录
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/top/windyvalley/magicsushi/`

## 备注

- 此任务只创建结构，不写业务代码