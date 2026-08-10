# 🍣 Magic Sushi

> 复刻经典 MTK 功能机游戏《魔法寿司》的 Android 单端实现。

**版本：** v1.0
**平台：** Android 8.0+ (API 26+)
**技术栈：** Kotlin + Jetpack Compose + ViewModel + StateFlow + Canvas

---

## ✨ 特性

- ✅ **7×7 棋盘** / 6 种寿司 / 60 秒倒计时（上限 90s）
- ✅ **双触控**：点击两次交换 + 拖动交换（30% 寿司宽度阈值）
- ✅ **连锁消除**：消除 → 下落 → 再消除的循环（最多 20 轮兜底）
- ✅ **智能奖励**：每次消除 +5s（ADR-004）
- ✅ **音效反馈**：swap / match / combo / tick 4 种 OGG
- ✅ **数据持久化**：最高分本地保存（SharedPreferences）
- ✅ **完整生命周期**：暂停 / 恢复 / 重启 / 退出
- ✅ **无网络依赖**：完全离线（NFR-3.3）

---

## 🎮 玩法

1. 启动 App，自动开始 60 秒倒计时
2. **点击**一个寿司选中，再**点击**相邻的寿司交换
   - 或**拖动**寿司到相邻位置直接交换
3. 若交换后 3 个或以上相同寿司连成一线 → 消除得分
4. 每次消除 +5 秒（最多 90 秒）
5. 倒计时结束 → 显示得分 + 最高分

---

## 🛠 构建

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更新
- JDK 17+（Android Studio 自带 JBR）
- Android SDK API 34
- Kotlin 1.9.22

### 编译 Debug APK

```bash
cd android-app
./gradlew assembleDebug
# 产出：app/build/outputs/apk/debug/app-debug.apk
```

### 安装到模拟器

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.windyvalley.magicsushi/.MainActivity
```

### 运行单元测试

```bash
cd android-app
./gradlew test
# 6 个测试文件 / 55 个测试用例
```

---

## 📁 项目结构

```
magic-sushi/
├── 01-requirements.md       # 需求文档 v1.1
├── 02-design.md             # 设计文档 v1.1
├── 02-arch-diagram.md       # 架构图
├── 02-adr/                  # 架构决策记录
├── 02-risk.md               # 风险登记
├── 02-tech-stack.md         # 技术选型
├── 03-decomposition.md      # 任务拆分
├── 03-task-graph.md         # 任务依赖图
├── 03-tasks/                # 30 个任务文件
├── 05-release-notes.md      # 发布说明 ⬅ 你在这里
├── DELIVERABLES.md          # 交付物清单
├── README.md                # 本文件
├── LICENSE                  # MIT
├── references/              # 美术素材、测试报告、截图
├── android-app/             # Android 工程
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/top/windyvalley/magicsushi/
│   │       │   │   ├── viewmodel/ # GameViewModel
│   │       │   │   ├── ui/        # Compose 组件（screen/canvas/theme）
│   │       │   │   ├── data/      # PrefsRepository
│   │       │   │   └── audio/     # SoundPlayer
│   │       │   ├── res/drawable/  # 6 个寿司 PNG（来自 EXL）
│   │       │   └── res/raw/       # 4 个 OGG 音效
│   │       └── test/              # ViewModel 测试
│   ├── engine/                    # 纯 Kotlin 模块（无 Android 依赖）
│   │   └── src/
│   │       ├── main/kotlin/.../engine/   # 23 个核心文件
│   │       └── test/kotlin/.../engine/   # 28 个测试文件 / 231 用例
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── references/
    ├── EXL-Magic-Sushi.md   # EXL 原始资源致敬
    ├── test-report-v1.0.md  # v1.0 测试报告
    └── screenshots/         # 32 张开发期截图
```

> **截图：** `references/screenshots/` 目录在 T-BUILD-001（debug APK 实跑）完成后补充。

---

## 🏗 架构

四层架构（[02-design.md](./02-design.md)）：

```
┌────────────────────────────────────┐
│ UI (Compose Canvas + Composable)  │  ← 渲染 + 触控
├────────────────────────────────────┤
│ State (ViewModel + StateFlow)      │  ← GameState 不可变快照
├────────────────────────────────────┤
│ Logic (纯 Kotlin 8 个核心文件)     │  ← Models/Board/Match/Gravity/Cascade/Score/Timer/GameState
├────────────────────────────────────┤
│ Storage (SharedPreferences)        │  ← PrefsRepository + SoundPlayer
└────────────────────────────────────┘
```

详细架构图见 [02-arch-diagram.md](./02-arch-diagram.md)。

---

## 🙏 致谢

- **MTK 平台经典游戏《魔法寿司》**（EXL 移植版）的原始美术和音效资源 — 见 [references/EXL-Magic-Sushi.md](./references/EXL-Magic-Sushi.md)
- **Jetpack Compose** — Google 现代化 Android UI 框架
- **Material 3** — 设计规范

---

## 📄 许可证

代码：[MIT](./LICENSE)
寿司图片和音效：归原作者所有，仅用于学习和复刻

---

## 📝 发布说明

见 [05-release-notes.md](./05-release-notes.md)