# T-ANDROID-003: AndroidManifest + Activity

**状态：** ✅ done
**优先级：** P0
**预估：** 2h
**Owner：** main agent
**依赖：** T-ANDROID-002
**可并行：** -

## 目标

编写 AndroidManifest.xml 和 MainActivity.kt（Compose 入口）。

## 来源

- 设计：02-design.md §5
- 需求：NFR-2.3（竖屏）

## 验收标准

- [ ] `AndroidManifest.xml` 注册 MainActivity
- [ ] 包名 `top.windyvalley.magicsushi`
- [ ] 启动 Activity
- [ ] 强制竖屏（`android:screenOrientation="portrait"`）
- [ ] 无 INTERNET 权限（确认离线）
- [ ] `MainActivity.kt` 是 ComponentActivity + setContent
- [ ] 引入 `MagicSushiTheme` 和 `GameScreen`

## 技术要点

- 用 `androidx.activity.ComponentActivity`
- `enableEdgeToEdge()` 处理状态栏
- 不需要任何 permission

## 产出物

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/top/windyvalley/magicsushi/MainActivity.kt`
- `app/src/main/java/top/windyvalley/magicsushi/MagicSushiApp.kt`（Application 类，可选）

## 备注

- 竖屏强制由 NFR-2.3 要求
- 2026-06-20 18:55 subagent-X 修复：MainActivity 接入 GameViewModel + 生命周期观察