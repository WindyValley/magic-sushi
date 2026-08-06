# Magic Sushi — 计时/暂停修复 + 开始界面与历史记录 实施计划

> **给接手者（可能是失忆后的我自己）**：这份文档假设你对本项目零上下文。
> 所有根因、代码位置、踩过的坑都写在里面，不要依赖对话历史。
> 读完「必读背景」再动手。

**创建时间**：2026-08-06
**分支**：`fix/batch1-critical`
**起始 commit**：`228c033`（引擎拆 module）

---

## 目标

用户提出的三批任务：

1. 触发消除后倒计时重置回 60s，废弃奖励时间机制，修复点击暂停没有真正暂停
2. 添加开始界面（【开始游戏】【历史记录】【退出游戏】）+ 历史记录界面，
   游戏启动先进开始界面；暂停界面【退出】和结束页【返回主菜单】都回开始界面
3. 修复主动重开/退出时当前成绩未计入历史成绩

---

## 必读背景

### 项目结构（拆 module 之后）

```
android-app/
├── settings.gradle.kts          include(":app") + include(":engine")
├── build.gradle.kts             根 plugins（含 kotlin.jvm 1.9.22 apply false）
├── engine/                      ★ 纯 Kotlin JVM module，零 Android 依赖
│   ├── build.gradle.kts         kotlin("jvm")，唯一外部依赖 coroutines-core
│   └── src/main/kotlin/top/windyvalley/magicsushi/engine/   12 个文件
│       src/test/kotlin/.../engine/                          14 个测试文件
└── app/                         Android application module
    ├── build.gradle.kts         api(project(":engine"))
    └── src/main/java/top/windyvalley/magicsushi/
        ├── MainActivity.kt      setContent { GameScreen(viewModel) }  ← 单屏
        ├── MagicSushiApp.kt     Application，持有 PrefsRepository / SoundPlayer
        ├── audio/               SoundPlayer
        ├── data/PrefsRepository.kt   ★ 只有 highScore + muted 两个键
        ├── ui/screen/           GameScreen / PauseDialog / GameOverDialog
        │                        ScoreOverlay / TimerDisplay / RewardOverlay
        ├── ui/canvas/           GameCanvas / SushiTile
        └── viewmodel/GameViewModel.kt   ★ 约 660 行
```

### ⚠️ 引擎层铁律

`:engine` 是**纯 Kotlin JVM module**，classpath 上没有 android.jar。
写 `import android.util.Log` 会**编译失败**。

`EnginePurityTest` 守着这条约定（反射探测 Android 类是否可加载）。
本计划所有新增持久化代码都属于 `app` module，不要往 engine 里放。

### 构建命令（本机实测可用）

```bash
cd /d/Coding/projects/magic-sushi/android-app

gradle :engine:test                          # 引擎测试，约 1 秒
gradle :engine:test :app:assembleDebug       # 全量 + 出 APK
gradle :app:compileDebugKotlin               # 只编 app

# 测试计数（BUILD SUCCESSFUL 不显示数量，要单独查）
grep -h "tests=" engine/build/test-results/test/*.xml \
  | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' \
  | awk '{s+=$1} END {print s" tests"}'
```

**基线：113 tests, 0 failures, 零编译告警。**

### ⚠️ 环境坑（已踩过）

- `gradlew` 是**占位脚本**，必须用系统 `gradle`（9.6.1）
- 本机**没有 JDK 17**，只有 21（JAVA_HOME）和 25（PATH）。
  engine module 不能用 `jvmToolchain(17)`，只能用
  `compilerOptions { jvmTarget.set(JVM_17) }`
- `execute_code` 工具在本环境不可用
- `read_file` 会把含中文的 .kt 误判为 binary，改用 `grep -n ""` 或 `sed -n`
- 没有 adb / emulator / robolectric，**UI 与手势只能真机验证**

---

## 三个 bug 的根因（已查证，不要重新猜）

### Bug 1：点击暂停没有真正暂停

`app/.../ui/screen/GameScreen.kt:59` 和 `:88`

```kotlin
var showPauseDialog by remember { mutableStateOf(false) }   // 纯 UI 局部状态
...
IconButton(onClick = { showPauseDialog = true }) { ... }    // ★ 只改 UI
```

`GameScreen.kt:127`：

```kotlin
if (showPauseDialog || state.phase == GamePhase.PAUSED) { PauseDialog(...) }
```

**根因**：点暂停按钮只把 `showPauseDialog` 置 true，**从不调
`viewModel.onPause()`**。对话框弹出了，但 VM 里的 `timerJob` 照常每秒递减，
`swapJob` 也没被取消。

`onPause()` 该做的三件事（`GameViewModel.kt:288-291`）一件都没发生：

```kotlin
fun onPause() {
    _state.update { it.copy(phase = GamePhase.PAUSED) }
    timerJob?.cancel()
    swapJob?.cancel()
}
```

`state.phase == GamePhase.PAUSED` 那半个条件只对**系统级暂停**（切后台，
`MainActivity.kt:43` 的 `ON_PAUSE`）生效，那条路径是通的。

所以是「两个暂停来源，只有一个真暂停」。

### Bug 2：历史记录功能实际不存在

用户描述为「重开/退出时成绩未计入历史」，实际查证：

`app/.../data/PrefsRepository.kt` 全部接口只有：

```kotlin
fun getHighScore(): Int
fun saveHighScore(score: Int)
fun isMuted(): Boolean
fun setMuted(muted: Boolean)
```

**只存了一个最高分数字**，没有任何「历史记录」数据结构。

`GameViewModel.kt:625`：

```kotlin
if (isNew) prefsRepo.saveHighScore(finalScore)   // 只在破纪录时写
```

- 破纪录 → 覆盖那个数字
- 没破纪录 → 什么都不记
- `onRestart()`（`GameViewModel.kt:274`）→ **完全没有写入调用**

**结论**：任务 3 不是「修写入时机」，而是历史记录功能要先建起来。
所以任务 3 必须和任务 2a 合并做，单独修没有意义。

### Bug 3：奖励时间机制的残留

`TimerEngine.rewardOnMatch` 的**行为已经是重置到 60s**（注释标 v1.0.3 改的），
但奖励语义的残留还在：

| 残留物 | 位置 |
|---|---|
| 函数名 `rewardOnMatch` + 返回 `(newRemaining, reward)` 二元组 | `TimerEngine.kt:136` |
| `const val REWARD_SECONDS = 5` | `TimerEngine.kt:74` |
| `const val MAX_SECONDS = 90`（注释说已不起作用） | `TimerEngine.kt:86` |
| `GameEvent.TimeReward(seconds)` | `GameEvent.kt:50` |
| `RewardOverlay` 渲染 "+5s" 飘字 | `ui/screen/RewardOverlay.kt` |
| `_events.tryEmit(GameEvent.TimeReward(reward))` | `GameViewModel.kt:568` |

**要做的是删掉残留语义**，不是改计时逻辑。

### 现有接线现状（重要）

`PauseDialog` 已有 `onQuit` 参数，`GameOverDialog` 已有 `onBackToMenu` 参数，
但接线是坏的：

`GameScreen.kt:140-144`：
```kotlin
onQuit = {
    showPauseDialog = false
    viewModel.onRestart()      // ★ 退出被接成了重开
},
```

`GameScreen.kt:156-166`：
```kotlin
GameOverDialog(
    ...
    onRestart = { ... },
    // ★ onBackToMenu 根本没传 → 用默认值 {} → 按钮点了没反应
)
```

### 已确认的设计决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 导航方案 | **手写 sealed class + when**，不引入 navigation-compose | 3 个静态屏幕，无参数传递/深链/返回栈需求。navigation-compose 的核心价值用不上 |
| 历史记录字段 | `{分数, 时间戳, 是否新纪录}` | 用户确认够用 |
| 裁剪策略 | **保留分数最高的 50 条**（不是最近 50 条） | 用户明确纠正过。满 50 条后低分局**不被记录**，而非挤掉旧的 |
| 持久化方案 | **DataStore**（顺势做掉 FIX_PLAN D8） | 多条记录 + Flow 读取正是它的场景。SharedPreferences 塞 JSON 是歪路 |
| 退出游戏 | **真结束进程** `finishAffinity()` + `exitProcess(0)` | 用户确认 |

---

## 批次划分

三批独立提交，每批可单独出 APK 验证。

| 批次 | 内容 | 风险 | 需真机验 |
|---|---|---|---|
| **A** | 任务 1：计时重置 + 废奖励 + 修暂停 | 低 | 是（暂停行为） |
| **B** | 任务 2a + 3：DataStore 历史记录 + 全路径写入 | 中 | 否（可单测） |
| **C** | 任务 2b：三个界面 + 导航 | 中 | 是（UI） |

**为什么 B 在 C 之前**：C 的历史记录界面要读 B 建立的数据层。
先建数据层并单测覆盖，再做 UI，避免 UI 和数据层同时不确定。

---

# 批次 A：计时重置 + 废奖励 + 修暂停

## Task A1：TimerEngine 废除奖励语义

**目标**：`rewardOnMatch` 改名为语义准确的 `resetOnMatch`，删掉奖励相关常量与返回值。

**文件**：
- 修改：`engine/src/main/kotlin/top/windyvalley/magicsushi/engine/TimerEngine.kt`
- 测试：`engine/src/test/kotlin/top/windyvalley/magicsushi/engine/TimerEngineTest.kt`

**Step 1：先读现状**

```bash
grep -n "" engine/src/main/kotlin/top/windyvalley/magicsushi/engine/TimerEngine.kt
```

**Step 2：改测试（RED）**

`TimerEngineTest.kt` 里所有 `rewardOnMatch` 调用改为 `resetOnMatch`，
断言从二元组 `(newRemaining, reward)` 改为单个 `Int`：

```kotlin
@Test
fun `消除后倒计时重置为 60 秒`() {
    assertEquals(TimerEngine.INITIAL_SECONDS, TimerEngine.resetOnMatch(50, listOf(match)))
}

@Test
fun `无消除时倒计时不变`() {
    assertEquals(50, TimerEngine.resetOnMatch(50, emptyList()))
}

@Test
fun `已超过 60 秒时也重置回 60`() {
    // 旧奖励机制可能让 remaining 超过 60（cap 是 90），重置不区分方向
    assertEquals(TimerEngine.INITIAL_SECONDS, TimerEngine.resetOnMatch(88, listOf(match)))
}
```

**Step 3：跑测试确认失败**

```bash
gradle :engine:test --tests "*TimerEngineTest*"
# 预期：Unresolved reference: resetOnMatch
```

**Step 4：改实现（GREEN）**

```kotlin
/**
 * 消除后重置倒计时。
 *
 * v1.0.3 起废弃「奖励时间」语义：不再 +5s 累加、不再有 90s cap，
 * 而是**每次成功消除都把倒计时拉回 [INITIAL_SECONDS]**。
 *
 * 为什么不叫 rewardOnMatch：那个名字承诺的是「奖励」（增量），
 * 与实际行为（重置）不符。名字说谎比没有名字更糟。
 *
 * @param remainingSeconds 当前剩余秒数
 * @param matches 本次消除的匹配。空列表 = 无消除 = 原样返回（FR-6.9）
 * @return 重置后的剩余秒数
 */
fun resetOnMatch(remainingSeconds: Int, matches: List<Match>): Int =
    if (matches.isEmpty()) remainingSeconds else INITIAL_SECONDS
```

同时删除：
- `const val REWARD_SECONDS: Int = 5`
- `const val MAX_SECONDS: Int = 90`
- `tick()` 里对 `MAX_SECONDS` 的 clamp（改为只 clamp 下界 0）

⚠️ **先 grep 确认 MAX_SECONDS 没有其他使用者**：
```bash
grep -rn "MAX_SECONDS\|REWARD_SECONDS" --include=*.kt engine/src app/src
```

**Step 5：跑测试确认通过**

```bash
gradle :engine:test --tests "*TimerEngineTest*"
```

**Step 6：不单独 commit**（与 A2、A3 一起，因为 A1 改完 app 会编译失败）

---

## Task A2：删除 GameEvent.TimeReward 与 RewardOverlay

**目标**：删掉奖励飘字的整条链路。

**文件**：
- 修改：`engine/src/main/kotlin/top/windyvalley/magicsushi/engine/GameEvent.kt`（删 `TimeReward`）
- 删除：`app/src/main/java/top/windyvalley/magicsushi/ui/screen/RewardOverlay.kt`
- 修改：`app/src/main/java/top/windyvalley/magicsushi/ui/screen/GameScreen.kt`（删 `RewardOverlay(...)` 调用）
- 修改：`engine/src/test/kotlin/.../GameEventTest.kt`（删 TimeReward 相关用例）

**Step 1：GameEvent 成员已查清（不用再查）**

```kotlin
sealed interface GameEvent {
    data class TimeReward(val seconds: Int) : GameEvent   // ← 只删这个
    data object SwapRejected : GameEvent                  // ← 保留，VM:500 在用
    data class NewRecord(val score: Int) : GameEvent       // ← 保留，VM:639 在用
}
```

**结论：事件流机制整个保留**，只删 `TimeReward` 一个成员。
`_events` / `viewModel.events` / `GameEventTest` 都不动。

`RewardOverlay` 是 `TimeReward` 的唯一消费者（`GameScreen.kt:151` 传入），
所以它整个文件可以删掉。

**Step 2：删除，然后编译找出所有断点**

```bash
gradle :engine:compileKotlin :app:compileDebugKotlin
```

让编译器列出引用点，逐个清理。预期断点：
- `GameViewModel.kt:568` — `_events.tryEmit(GameEvent.TimeReward(reward))`
- `GameScreen.kt:148-152` — `RewardOverlay(events = viewModel.events)` 调用
- `GameEventTest.kt` — 若有用 TimeReward 构造的用例

---

## Task A3：GameViewModel 适配 + 修暂停 bug

**目标**：VM 调用新 API；`GameScreen` 的暂停按钮真正调 `onPause()`。

**文件**：
- 修改：`app/src/main/java/top/windyvalley/magicsushi/viewmodel/GameViewModel.kt:527, 568`
- 修改：`app/src/main/java/top/windyvalley/magicsushi/ui/screen/GameScreen.kt:88`

**Step 1：VM 侧改调用**

`GameViewModel.kt:527` 附近：

```kotlin
// 旧
val (newRemaining, reward) = TimerEngine.rewardOnMatch(remaining, allMatches)
// 新
val newRemaining = TimerEngine.resetOnMatch(remaining, allMatches)
```

删掉 `GameViewModel.kt:568` 的 `_events.tryEmit(GameEvent.TimeReward(reward))`。

**Step 2：修暂停 bug —— 关键改动**

`GameScreen.kt:88`：

```kotlin
// 旧：只改 UI 局部状态，timerJob 照跑
IconButton(onClick = { showPauseDialog = true })

// 新：走 VM，真正停掉 timerJob / swapJob
IconButton(onClick = { viewModel.onPause() })
```

**Step 3：简化对话框显示条件**

`showPauseDialog` 这个局部状态现在是多余的 —— `phase == PAUSED`
已经是唯一真相。删掉它，条件简化为：

```kotlin
if (state.phase == GamePhase.PAUSED) { PauseDialog(...) }
```

⚠️ **连带清理**：`GameScreen.kt` 里 5 处 `showPauseDialog = false` 全部删掉
（132/136/140/163 行附近）。其中 163 行那个注释提到「修 δ 发现的 bug：
如果用户在 pause dialog 弹出期间 game over，点重玩要同时清掉标志」——
删掉局部状态后这个 bug 自然不存在了，注释也一并删。

**Step 4：编译 + 全量测试**

```bash
gradle :engine:test :app:compileDebugKotlin
```

**Step 5：出 APK，commit**

```bash
gradle :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk \
   "/c/Users/Windy/Desktop/magic-sushi-debug-batchA.apk"
```

commit message 要点：暂停 bug 的根因（UI 局部状态 vs VM 状态）、
奖励语义删除清单、`showPauseDialog` 为何是多余的。

**⚠️ 真机验证点**：
1. 点暂停按钮 → 倒计时**必须停住**（这是 bug 本体）
2. 暂停时若有 cascade 动画进行中 → 动画也该停
3. 继续 → 倒计时从停住的数字接着走
4. 消除一次 → 倒计时跳回 60s
5. 不再出现 "+5s" 飘字

---

# 批次 B：DataStore 历史记录 + 全路径写入

## Task B1：加 DataStore 依赖

**文件**：`app/build.gradle.kts`

```kotlin
// 历史记录持久化（FIX_PLAN D8：替代 SharedPreferences 的同步主线程 IO）
implementation("androidx.datastore:datastore-preferences:1.0.0")
```

⚠️ 仓库配置在 `settings.gradle.kts`，用的是阿里云镜像。若拉不到包，
检查 `maven.aliyun.com/repository/google` 是否含该 artifact。

**验证**：`gradle :app:dependencies --configuration debugRuntimeClasspath | grep datastore`

---

## Task B2：定义 GameRecord 数据模型

**放在哪里**：这是**纯数据类，无 Android 依赖 → 放 engine module**。

**文件**：新建 `engine/src/main/kotlin/top/windyvalley/magicsushi/engine/GameRecord.kt`

```kotlin
package top.windyvalley.magicsushi.engine

/**
 * 一局游戏的历史记录。
 *
 * 放在 engine module 而非 app：这是纯数据，不含任何 Android 类型。
 * 序列化格式由 app 层的 HistoryRepository 决定，引擎不关心。
 *
 * @property score 本局最终得分
 * @property timestampMillis 本局结束时刻（epoch millis）
 * @property isNewRecord 本局是否打破了当时的最高分
 */
data class GameRecord(
    val score: Int,
    val timestampMillis: Long,
    val isNewRecord: Boolean,
)
```

**测试**：`engine/src/test/kotlin/.../GameRecordTest.kt`

⚠️ 排序/裁剪逻辑也放 engine（纯函数，可测）：

```kotlin
object GameHistory {
    const val MAX_RECORDS = 50

    /**
     * 插入新记录并按分数降序裁剪到 [MAX_RECORDS] 条。
     *
     * ⚠️ 保留的是**分数最高的 50 条**，不是最近 50 条。
     * 满 50 条后，低于第 50 名的新记录**不会被保存**。
     * 同分时新记录排在前（时间戳降序）。
     */
    fun insert(existing: List<GameRecord>, new: GameRecord): List<GameRecord> =
        (existing + new)
            .sortedWith(compareByDescending<GameRecord> { it.score }
                .thenByDescending { it.timestampMillis })
            .take(MAX_RECORDS)
}
```

**测试要覆盖**：
- 空列表插入
- 未满 50 条时插入
- 满 50 条且新记录分数够高 → 挤掉最低分
- 满 50 条且新记录分数不够 → **不被保存**（列表内容不变）
- 同分时新记录在前
- 边界：正好第 50 名同分

---

## Task B3：HistoryRepository（DataStore）

**文件**：新建 `app/src/main/java/top/windyvalley/magicsushi/data/HistoryRepository.kt`

要点：
- `Flow<List<GameRecord>>` 供 UI 订阅
- `suspend fun addRecord(record: GameRecord)`
- 序列化：JSON 字符串存单个 preferences key（记录数少，无需 Room）
  或用 `stringSetPreferencesKey` + 每条一个字符串
- **不要**在构造函数里做同步 IO（这是 D8 要避免的病）

⚠️ 用 `org.json.JSONArray`（Android 内置，无需依赖）或手写 CSV 解析。
不要引入 kotlinx.serialization —— 为 3 个字段加一个序列化框架不值得。

**测试**：`app/src/test/...`（app module 目前**没有测试目录**，需要新建；
或者把序列化逻辑抽成纯函数放 engine 里测，Repository 本身只做 IO）

**倾向后者**：序列化/反序列化是纯函数，放 engine 测；
`HistoryRepository` 只剩薄薄一层 DataStore 读写。

---

## Task B4：接入 VM，修全路径写入

**文件**：`app/src/main/java/top/windyvalley/magicsushi/viewmodel/GameViewModel.kt`

**关键**：成绩写入必须覆盖**三条路径**，这是任务 3 的本体：

| 路径 | 函数 | 现状 |
|---|---|---|
| 时间到自然结束 | `onGameOver()` (line 621) | 只在破纪录时写 highScore |
| 主动重开 | `onRestart()` (line 274) | **完全没写** |
| 退出到主菜单 | 新增 | 不存在 |

**做法**：抽一个私有函数，三处都调：

```kotlin
/**
 * 把当前局成绩写入历史。
 *
 * ⚠️ 必须在**所有**结束路径调用：自然结束、主动重开、退出到主菜单。
 * 早期只有 onGameOver 写 highScore，导致主动重开的成绩凭空消失
 * （用户报告的 bug）。
 *
 * phase 为 IDLE 或分数为 0 时跳过 —— 没开始过的局不该产生记录。
 */
private fun persistCurrentRound() {
    val s = _state.value
    if (s.phase == GamePhase.IDLE || s.score == 0) return
    ...
}
```

⚠️ **注意竞态**：`onRestart()` 里要先写入再重置状态，顺序不能颠倒。

**测试**：VM 测试需要 Android 环境（viewModelScope），本项目**没有 robolectric**。
所以：
- `GameHistory.insert` 的逻辑在 engine 里单测（已在 B2 覆盖）
- 三条路径都调用了 `persistCurrentRound` 这件事，靠**代码审查 + 真机验证**

---

# 批次 C：开始界面 + 历史记录界面 + 导航

## Task C1：定义 AppScreen 导航状态

**文件**：新建 `app/src/main/java/top/windyvalley/magicsushi/ui/navigation/AppScreen.kt`

```kotlin
/**
 * 应用级屏幕状态。
 *
 * 为什么手写而不用 navigation-compose：3 个静态屏幕，
 * 无参数传递、无深链、无返回栈需求 —— navigation-compose 的
 * 核心价值（路由字符串 + 参数序列化 + 返回栈）这里一个都用不上。
 * 若将来加关卡选择/设置页/深链分享，再引入才划算。
 */
sealed interface AppScreen {
    data object Menu : AppScreen
    data object Game : AppScreen
    data object History : AppScreen
}
```

放在哪里管理状态？两个选择：
- **A**：`MainActivity` 里 `var current by remember { mutableStateOf<AppScreen>(Menu) }`
- **B**：新建 `AppViewModel` 持有

**倾向 A**：屏幕切换是纯 UI 关注点，进程死亡后回到 Menu 是合理行为，
不需要额外的 ViewModel。若以后要跨进程恢复，改用 `rememberSaveable`。

---

## Task C2：MainMenuScreen

**文件**：新建 `app/src/main/java/top/windyvalley/magicsushi/ui/screen/MainMenuScreen.kt`

三个按钮：【开始游戏】【历史记录】【退出游戏】

⚠️ 复用现有配色（从 `GameScreen.kt` / `PauseDialog.kt` 抄）：
- 背景 `SushiBgDark`
- 主按钮 `Color(0xFFE85D2F)` 橙
- 次按钮 `OutlinedButton` + `Color(0xFFFFE8C5)`
- 对话框容器 `Color(0xFF2A1810)`

**退出游戏**的实现：

```kotlin
// 真结束进程（用户明确要求）
val activity = LocalContext.current as? Activity
onExit = {
    activity?.finishAffinity()
    exitProcess(0)
}
```

---

## Task C3：HistoryScreen

**文件**：新建 `app/src/main/java/top/windyvalley/magicsushi/ui/screen/HistoryScreen.kt`

- 读 `HistoryRepository.recordsFlow`
- 按分数降序显示（数据层已排好，UI 不再排）
- 每行：排名、分数、日期时间、新纪录标记
- 空状态：「还没有游戏记录」
- 返回按钮 → Menu

⚠️ 时间格式化用 `java.time.format.DateTimeFormatter`（minSdk 26，可用）。

---

## Task C4：导航接线 + 修坏掉的回调

**文件**：
- 修改：`app/src/main/java/top/windyvalley/magicsushi/MainActivity.kt`
- 修改：`app/src/main/java/top/windyvalley/magicsushi/ui/screen/GameScreen.kt`

**MainActivity**：

```kotlin
setContent {
    MagicSushiTheme {
        var screen by remember { mutableStateOf<AppScreen>(AppScreen.Menu) }
        when (screen) {
            AppScreen.Menu -> MainMenuScreen(
                onStartGame = { screen = AppScreen.Game },
                onHistory = { screen = AppScreen.History },
                onExit = { finishAffinity(); exitProcess(0) },
            )
            AppScreen.Game -> GameScreen(
                viewModel = viewModel,
                onBackToMenu = { screen = AppScreen.Menu },
            )
            AppScreen.History -> HistoryScreen(
                onBack = { screen = AppScreen.Menu },
            )
        }
    }
}
```

⚠️ **`GameScreen` 现在的 `LaunchedEffect(Unit)` 会在 IDLE 时自动 startGame**
（`GameScreen.kt:62-66`）。从 Menu 进入时这是想要的行为，但从 Game 退回
Menu 再进入时，phase 可能是 GAME_OVER —— 需要确认这时能否正确重开。

**修两个坏掉的回调**（见「现有接线现状」）：

```kotlin
// PauseDialog 的 onQuit：现在接的是 onRestart()，改为退出到主菜单
onQuit = {
    viewModel.onQuitToMenu()   // 内部要 persistCurrentRound()
    onBackToMenu()
},

// GameOverDialog 的 onBackToMenu：现在根本没传，按钮点了没反应
onBackToMenu = {
    onBackToMenu()
},
```

---

## Task C5：全量验证 + 出 APK

```bash
gradle :engine:test :app:assembleDebug
# 连跑 3 次确认不 flaky
for i in 1 2 3; do gradle :engine:test --rerun-tasks 2>&1 | grep -E "BUILD|FAILED"; done
```

**真机验证清单**：
1. 启动 → **先进开始界面**（不是直接进游戏）
2. 【开始游戏】→ 进游戏，倒计时正常走
3. 游戏中暂停 → 【退出】→ **回开始界面**（不是重开、不是退出 App）
4. 再看【历史记录】→ 刚才那局**成绩在列表里**（任务 3 的验证点）
5. 玩到时间结束 → 【返回主菜单】→ 回开始界面
6. 历史记录里有这局
7. 主动重开若干次 → 每局成绩都在历史里
8. 【退出游戏】→ **App 真的退出**，不留后台
9. 历史记录按分数降序，不是时间序
10. 攒够 50 条后，低分局不再进入列表

---

## 风险与开放问题

### 高风险项

1. **`GameScreen` 的自动 startGame 逻辑**（C4 提到）
   从 Menu 反复进出游戏时，phase 状态机可能有边界情况。
   `GameViewModel` 是 Activity 级单例（`by viewModels()`），
   退回 Menu 时 VM **不会销毁**，状态会留着。
   → 进入 Game 屏幕时可能需要显式 `startGame()` 而非依赖 phase 判断。

2. **`GameViewModel` 的 `onQuitToMenu` 需要新增**
   现在没有「退出到主菜单」这个 VM 方法。要新增，且必须
   `persistCurrentRound()` + 重置 phase 到 IDLE。

3. **DataStore 首次读取是异步的**
   HistoryScreen 打开瞬间可能拿到空列表再填充。
   → 要有 loading 状态或至少不闪烁。

### 开放问题（做到时再定）

- ~~`GameEvent` 删掉 `TimeReward` 后是否还有其他成员？~~
  **已查清**：还有 `SwapRejected`（VM:500 在用）和 `NewRecord`（VM:639 在用）。
  事件流机制整个保留，只删 `TimeReward` 一个成员。
- 历史记录要不要显示「本局最大连击」？用户说当前三个字段够用，但 `combo`
  已经在 GameState 里，加进来成本很低。**先不加，YAGNI。**
- 暂停界面还需不需要【重新开始】按钮？现在有，且行为正确。保留。

---

## 完成标准

- [ ] 批次 A：暂停真的停住倒计时；消除后回 60s；无 "+5s" 飘字
- [ ] 批次 B：`GameHistory.insert` 单测覆盖 6 种边界；三条路径都写入
- [ ] 批次 C：启动进 Menu；两个回调修好；历史记录按分数降序
- [ ] 每批次单独 commit，附完整 CHANGELOG 风格 message（中文，含根因/修法/副作用）
- [ ] 引擎测试保持全绿，零编译告警
- [ ] `EnginePurityTest` 仍然通过（新增代码没往 engine 里塞 Android 依赖）
- [ ] FIX_PLAN.md 的 D8 标记为完成（DataStore 迁移在批次 B 顺势做掉）

---

## 给接手者的提醒

- 用户偏好**分批次提交**，每个 commit 附完整 CHANGELOG 风格 message
  （中文，含根因、修法、副作用）。不要堆一堆改动一次性提交。
- 用户会**真机验证**，单测全绿不够。涉及 Compose 渲染层/动画/手势的修改，
  必须提醒装 APK 验证。
- 遇到测试红了，**先分清是「代码错」还是「测试预期错」**，
  不要直接放宽断言。
- 重构若带来行为变化（比如动画时长），**必须主动上报**，不要当作
  「纯结构调整」悄悄带过。
