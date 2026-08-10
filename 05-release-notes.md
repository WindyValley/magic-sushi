# Magic Sushi 发布说明

> 基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。
> 版本遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.0.0] - 2026-08-11

第一个达到发布标准的版本。结束 0.x 初始开发阶段 —— 0.x 期间不保证存档格式
与公开 API 稳定，1.0 之后要保证。

功能上相比 v0.2.1 只修了 4 个渲染层 bug，没加新功能。SemVer 的 1.0.0
表达的是**承诺**而不是功能量（semver.org 第 5 条："1.0.0 版本定义了公共 API"）。

### 修复：消除后的下落动画

四个 bug 是同一处代码的四层问题，逐层剥开：

1. **落地回弹** —— `animOffsetY` 已是一个 tween 的输出，又被当作第二个
   tween 的 `targetValue`。两级串联产生二阶滞后：第一级到位时第二级还在
   追前一刻的值，冲过静止位再被拉回。拆成两条独立路径（拖拽走 tween，
   下落直接相加）。

2. **下落偏移量算错** —— UI 和 engine 各算了一份，`SpawningIn` 那份是错的：
   UI 用 `spawnFromRow`，正确的是 `row - spawnFromRow`。顶部空洞的新 tile
   本该落 5 格，只落了 3 格 —— 起点还在棋盘内。改为统一取 engine 的
   `TileRenderState.offsetY`，UI 只做一次符号转换。

3. **新生成 tile 闪一下才下落** —— 动画状态的初值取了落点（0），首帧渲染
   在目标位置，下一帧副作用才置位到起点。

4. **已有 tile 下落时闪** —— 第 3 项只改了初值，而初值只在首次组合求值。
   已有 tile 在 `Stable` 时状态就建好了，之后 `Falling` 帧到来不会重建。
   置位改到**组合期**同步执行，本帧渲染即用起点值。

### 变更：下落曲线改为加速

`tween` 默认 `FastOutSlowInEasing` 是尾部减速，tile 接近落点会缓一下，
像飘下来。换成 `CubicBezierEasing(0.33f, 0f, 0.67f, 0.2f)`，速度单调上升。

淡出仍用默认曲线 —— 消失是视觉过渡，不是物理运动。

### 工程

- **补上 release 签名配置**。密钥与密码走 `keystore.properties`
  （不进仓库，见 `keystore.properties.example`）。缺密钥时产出 unsigned APK，
  不阻塞 CI。
- **换掉假的 Gradle wrapper**。`gradle-wrapper.jar` 此前是 446 字节的文本
  占位符，`gradlew`/`gradlew.bat` 也是占位脚本 —— clone 下来无法构建。
- **移除 `FIX_PLAN.md` / `PROJECT_STATUS.md`**。过程文档与 git 历史构成
  双份真相，且实测已落后。发布状态以 git tag + 本文件为准。
- 修正 README 目录树多处失实（engine 模块位置、文件数、测试数、死链）。

### 测试

237 个用例全绿（engine 231 + app 6），零跳过，零编译告警。

其中 3 条是本版新增的 spawn 帧连续性诊断测试。

新增的 3 条测试钉住 engine 帧数据的连续性：spawn tile 在 frame2 的位置与
最终棋盘一致、frame1 不在 spawn 格子上画东西、同一 tile 跨帧不换格子。
将来改动 `generateFrames` 的位置计算会立刻暴露。

---

## [0.2.1] - 2026-08-10

### 变更
- 用 🍣 emoji 重新渲染全套图标（5 个密度的 `ic_launcher` + `ic_launcher_round`），
  首次补上自适应图标（`mipmap-anydpi-v26`）。
- 启动封面随图标一起更新 —— `themes.xml` 的 `windowSplashScreenAnimatedIcon`
  引用 `@mipmap/ic_launcher`，一处改动两处生效。

### 修复
- 旧图标在圆形遮罩下缺角、小字号渲染发糊。根因是直接按目标字号渲染会命中
  字体内嵌的低分辨率 strike；改为先在 1024px 渲染再 LANCZOS 下采样。
- 图标底色对齐 `sushi_bg_light` (#FFE8C5)。此前脚本里凭印象写了 #FFF3E0，
  冷启动瞬间会看到色块边界。

---

## [0.2.0] - 2026-08-10

### 新增
- **设置页面** —— 静音开关终于有了 UI 入口（此前实现存在但无处可点）、
  清空历史记录、关于。
- **对局快照** —— 切后台自动挂起对局，主菜单出现「继续上局」入口。
  快照不设失效时间，恢复后自然失效。
- **退出二级确认** —— 暂停面板的退出按钮改为让玩家选「保留进度」或
  「结束本局」。0 分时退化为两按钮（快照仅有初始棋盘，无进度可留）。

### 变更
- **最高分改为从历史记录派生**（`deriveHighScore`），消除双份真相。
  此前独立存储的最高分与历史记录会不一致。
- 「破纪录」判据归一 —— 正分检查内置进 `HighScoreRules.isNewRecord`，
  不再散落在调用方。
- 离开对局的清理逻辑收敛成 `RoundTeardown` 纯函数（清 10 字段，
  留 `isMuted` + `highScore`）。
- 设置页去掉「清空最高分」—— 最高分已由历史派生，单独清它没有意义。
- 确认弹窗文案分层：动作名进按钮，说明性内容归正文。

### 修复
- 结算面板切后台回来变成暂停面板。`onPause()` 改为只在 PLAYING phase 触发。
- 重开一局时分数播了一次归零动画。
- 离开对局后主菜单开新局，首帧闪过上一局的分数。
- 删除永远看不到的「最高分闪烁」效果 —— 它只在被遮挡时触发。

---

## [0.1.1] - 2026-08-09

### 变更
- 历史列表移除冗余的「🏆 最高分」标记 —— 列表本身按分数排序，
  第一行就是最高分。

---

## [0.1.0] - 2026-08-08

首个可运行版本。核心玩法完整。

> ⚠️ 此条目此前记作 `[1.0.0] - 2026-06-20`。那是代码首次写成的日期，
> 当时 `versionName = "1.0"`。后来按 SemVer 重排为 0.x 序列（初始开发阶段
> 不该占用 1.0.0），本文件的版本号随之修正，以与 git tag 对齐。

**核心功能：**
- 7×7 棋盘，6 种寿司（🍣 虾 / 🍙 饭团 / 🦐 鲜虾 / 🥡 寿司卷 / 🍤 天妇罗 / 🍱 便当）
- 60 秒倒计时，消除 +5s（上限 90s）

**交互：**
- 点击两次交换（FR-2.1）
- 拖动交换（FR-2.2，30% 寿司宽度阈值）
- 选中高亮（1.15x 放大 + 红框）
- 拖动中半透明（alpha=0.7）
- 无效交换自动回弹（150ms）

**游戏机制：**
- 三连检测（横/竖），支持 3-7 连
- 连锁消除（重力 + 补充新 tile + 再检测）
- 得分公式：基础分 × 长度加成 × 连击倍数
- 4 种音效：swap / match / combo / tick（最后 10s）

**UI 组件：**
- 顶部：TimerDisplay 倒计时 + +Ns 飘字
- 棋盘：GameCanvas 7×7 渲染
- 底部：ScoreOverlay 分数 + 最高分
- 弹窗：PauseDialog 暂停 / GameOverDialog 结算

**数据：**
- 最高分与静音开关本地持久化
- 完全离线运行（无网络权限）

**修复：**
- 冷启动即崩 —— `data object` 不能直接交给 `rememberSaveable`

---

## 后续计划 (v1.1+)

- [ ] 难度递增（开局棋盘更密）
- [ ] 道具系统（炸弹 / 横向消除 / 纵向消除）
- [ ] 成就系统
- [ ] 排行榜（在线）
- [ ] 多语言支持

---

## 技术栈

- Kotlin 1.9.22 / JVM target 17
- Jetpack Compose BOM 2024.02.00
- ViewModel + StateFlow
- Canvas + ImageBitmap
- SoundPool
- DataStore（历史记录、快照、设置；带 `SharedPreferencesMigration`，
  从 0.1.x 的 SharedPreferences 数据自动迁移，老用户的最高分不丢）
- minSdk 26 / targetSdk 34
