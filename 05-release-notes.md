# Magic Sushi 发布说明

> 基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。
> 版本遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-06-20

### 🎉 首次发布

**核心功能：**
- 7×7 棋盘，6 种寿司（🍣 虾 / 🍙 饭团 / 🦐 鲜虾 / 🥡 寿司卷 / 🍤 天妇罗 / 🍱 便当）
- 60 秒倒计时，消除 +5s（上限 90s）
- 完整的 1:1 复刻 + 现代化增强

**交互：**
- 点击两次交换（FR-2.1）
- 拖动交换（FR-2.2，30% 寿司宽度阈值）
- 选中高亮（1.15x 放大 + 红框）
- 拖动中半透明（alpha=0.7）
- 无效交换自动回弹（150ms）

**游戏机制：**
- 三连检测（横/竖），支持 3-7 连
- 连锁消除（重力 + 再检测）
- 得分公式：基础分 × 长度加成 × 连击倍数
- 4 种音效：swap / match / combo / tick（最后 10s）

**UI 组件：**
- 顶部：TimerDisplay 倒计时 + +Ns 飘字
- 棋盘：GameCanvas 7×7 渲染
- 底部：ScoreOverlay 分数 + 最高分
- 弹窗：PauseDialog 暂停 / GameOverDialog 结算

**数据：**
- 最高分本地保存（SharedPreferences）
- 静音开关持久化
- 完全离线运行（无网络权限）

**测试：**
- 6 个 Engine 单元测试（55 个用例）
- Kotlin 编译通过
- 详见 `references/test-report-v1.0.md`

**技术栈：**
- Kotlin 1.9.22
- Jetpack Compose BOM 2024.02.00
- ViewModel + StateFlow
- Canvas + ImageBitmap
- SoundPool
- SharedPreferences

### 已知限制
- Android 模拟器实机测试**待完成**（T-BUILD-001）
- 真实设备性能测试**待完成**（T-TEST-001）

### 后续计划 (v1.1+)
- [ ] 难度递增（开局棋盘更密）
- [ ] 道具系统（炸弹 / 横向消除 / 纵向消除）
- [ ] 成就系统
- [ ] 排行榜（在线）
- [ ] 多语言支持