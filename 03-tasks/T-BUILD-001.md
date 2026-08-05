# T-BUILD-001: debug APK 编译 + 安装到模拟器

**状态：✅ done (06-21 00:30, main agent + 修复)
**优先级：** P0
**预估：** 1h
**Owner：** main agent
**依赖：** T-VM-002, T-UI-* 全集
**可并行：** -

## 目标

第一次成功编译 debug APK 并安装到 Android 模拟器。

## 来源

- 验收标准：AC-4（`app-debug.apk` 能直接安装到 Android 8.0+）
- 需求：NFR-2.1（API 26+）

## 验收标准

- [ ] `./gradlew assembleDebug` 成功
- [ ] `app-debug.apk` 存在于 `app/build/outputs/apk/debug/`
- [ ] APK 安装到 Android 模拟器（API 34）
- [ ] App 启动后看到游戏棋盘
- [ ] 无 crash

## 技术要点

- 路径：`C:\Users\Windy\LoopTimer` 类似经验（但这是新项目）
- 模拟器：Pixel 7 API 34
- 启动：`adb install app-debug.apk` + `adb shell am start`

## 产出物

- `app-debug.apk` 编译产出
- 模拟器运行截图

## 备注

- 这是关键里程碑：**从设计到运行的转折点**
- 任何编译错误立即修复，不留到后面