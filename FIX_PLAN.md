# Magic Sushi 修复方案

本文档基于代码审查发现的 10 个设计问题，按优先级从高到低给出具体修复方案。每个问题包含：**根因 → 方案 → 代码变更**。

---

## 📊 实施结算（2026-08-06 更新）

**18 项中 16 项已完成**，剩 2 项按原计划等需求触发。

测试基线：**111 个全绿，零编译告警**，`--rerun-tasks` 连跑 3 次稳定。

| 项 | 状态 | commit | 备注 |
|----|------|--------|------|
| P0-1 swapProcessing 异常安全 | ✅ | `9d041a5` | try/finally |
| P0-2 SoundPlayer 生命周期 | ✅ | `56325a6` | 依赖提升到 Application |
| P0-3 动画协程可取消 | ✅ | `9d041a5` | swapJob + phase 守卫 |
| P1-1 CascadeAnimator 抽出 | ✅ | `9b96698` | **抽出后测试抓到真 bug**，见下 |
| P1-2 双重重力计算 | ✅ | `54130f1` | 参数不一致，两批不同源结果 |
| P1-3 GravityEngine 职责分离 | ✅ | `6efaf19` | **删开关而非加参数**，见下 |
| P1-4 GameCanvas 分支合并 | ✅ | `66a9bb7` | 90 行 → 45 行 |
| P2-1 MatchEngine 分配优化 | ✅ | `b0eb294` | **落地方案与计划不同**，见下 |
| P2-2 清理 fun main() | ✅ | `79418fc` | 删 1763 行；先补 ModelsTest 再删 |
| P2-3 Application DI 入口 | ✅ | `56325a6` | 已由 P0-2 覆盖 |
| D1 tile id 撞号 | ✅ | `f90480c` | TileIdGenerator 全局递增 |
| D2 奖励飘字漏播 | ✅ | `68229c7` | GameEvent SharedFlow |
| D3 Array → List | ✅ | `b0eb294` | 顺带带走 P2-1 |
| D4 animFrame → BoardPresentation | ✅ | `468dd63` + `6a3957b` | **拆两半做**，见下 |
| D5 静音三份拷贝 | ✅ | `56325a6` | PrefsRepository 单一数据源 |
| D6 默认值 RNG | ✅ | `68229c7` | 默认空棋盘 |
| D7 GameState 拆分 | ⏸️ 暂缓 | — | 现 18 字段；等新玩法触发 |
| D8 DataStore 迁移 | ⏸️ 暂缓 | — | 等新持久化需求 |

### 与原方案的四处偏差（重要）

按方案照抄会踩空，这几处实际做法与本文档正文不同：

**P2-1 — 目的达成，方案换了**

计划：改传索引器 `(Int) -> SushiTile?`，零中间容器。
实际：D3 换 `List` 时顺手带走，中间容器**仍保留**：

```kotlin
val column = List(board.size) { r -> board.grid[r][col] }
```

这是有意取舍：保留一次 7 元素的短命分配，换 `detectLineMatches` 对横纵
两轴保持**统一的 `List<SushiTile?>` 消费者**，两轴共用一条代码路径，
bug 面减半。索引器方案能省这次分配，但多一层间接；7 元素 List 在 JVM 上
是逃逸分析容易吃掉的短命对象，收益不值那层复杂度。

⚠️ 别照着正文的索引器方案再改一遍 —— 目的已达成。

**P1-3 — 删开关，而非"分离职责"**

排查发现 `doRefill = true` 在生产代码里**已无任何显式调用方**，唯一的
`true` 来自专测它的测试。但 `CascadeEngine` 靠**默认值**隐式依赖它完成
cascade 连锁 —— 正因隐式，`grep "doRefill"` 找不到它，差点被当死代码删掉。

结论：直接删掉 `doRefill` 与 `rng` 两个参数，补充改为调用方显式一步。
详见该节末尾的追加说明。

**P1-1 — 抽出后测试抓到一个真实缺陷**

原实现用 `if (roundIdx < cascades.size - 1) delay(gapMs)` 判断轮间间隙。
`shouldContinue` 中途返回 false 提前中止时，最后一轮**仍会等一个 gap** ——
玩家在超时/暂停后感知到画面多停顿 100ms。

修法是把间隙移到**下一轮开头**（`if (roundIdx > 0)`）。

⚠️ **附带行为变化**：单轮 cascade 总时长 600ms → 500ms。去掉的是帧 2
显示完之后的无视觉内容空等。已真机确认手感正常。

**D4 — 拆成两半，且前半不在原方案里**

原方案只写了 `animFrame` → `BoardPresentation`（类型安全）。实际做了两半：

1. **索引体系收口**（`468dd63`）— 原方案没有这一项。废除 `AnimationEngine`
   的 `visualId` 负数编号空间，让 `tile.id` 成为全 App 唯一身份。
   **这一半修掉了用户真机报告的手势错位 bug**（`pointerInput(type)` 捕获
   过期 row/col，type 仅 5 种取值导致闭包不重建）。
2. **`BoardPresentation` 密封类型**（`6a3957b`）— 即原方案内容。

先做 1 是因为手势 bug 优先级更高；1 完成后 2 的改造面反而更小。

### 验证方法上的两点经验

- **密封类型要验证真的兑现承诺**：临时注入第三个分支 `data object ProbeOnly`，
  确认编译器在 `GameCanvas` 两处点名报错，而非只看"编译通过"。探针已撤除。
- **删代码前先补覆盖**：P2-2 删 `main()` 时发现 `Models.kt` 那 8 条断言
  **没有任何 JUnit 对应**（其余 7 个引擎都有）。先建 `ModelsTest.kt`
  迁移跑绿，再删源文件 —— 否则是在删测试覆盖。

---

## 🔴 P0 — 必须修复（影响游戏稳定性）

### 1. swapProcessing 无异常安全保护

**根因**：`onSwapAttempt()` 中 `swapProcessing = true` 后，三个 exit 路径都手动 `= false`，但没有 `try/finally`。任何运行时异常（NPE、IndexOutOfBounds）会导致游戏永久冻结。

**方案**：用 `try/finally` 包裹，或使用 `kotlin.runCatching`。

**变更文件**：`viewmodel/GameViewModel.kt`

```kotlin
// 修改前
private fun onSwapAttempt(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
    viewModelScope.launch {
        swapProcessing = true
        // ... 业务逻辑 ...
        // 三个 return 点各写一次 swapProcessing = false
        swapProcessing = false
    }
}

// 修改后
private fun onSwapAttempt(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
    viewModelScope.launch {
        try {
            swapProcessing = true
            // ... 业务逻辑保持不变 ...
        } catch (e: Exception) {
            // 记录异常日志，避免静默失败
            android.util.Log.e("GameViewModel", "onSwapAttempt failed", e)
        } finally {
            swapProcessing = false
        }
    }
}
```

**验收**：在 swap 动画中间任意位置抛异常 → `swapProcessing` 被重置为 `false` → 用户可继续操作。

---

### 2. SoundPlayer 生命周期与 ViewModel 错配

**根因**：
- `MainActivity` 每次重建（旋转屏幕）时新建 `SoundPlayer` 和 `PrefsRepository`
- `GameViewModel` 跨配置变更存活，**持有旧 Activity 创建的旧实例**
- 旧 Activity `onDestroy()` 调用 `soundPlayer.release()` → SoundPool 释放
- 但 ViewModel 继续调用 `soundPool?.play()` → 静默失败

**方案 A（推荐）**：把依赖移到 `Application` 中，Activity 只取引用。

**变更文件**：`MagicSushiApp.kt`、`MainActivity.kt`

```kotlin
// MagicSushiApp.kt
class MagicSushiApp : Application() {
    lateinit var prefsRepo: PrefsRepository
        private set
    lateinit var soundPlayer: SoundPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        prefsRepo = PrefsRepository(this)
        soundPlayer = SoundPlayer(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        if (::soundPlayer.isInitialized) soundPlayer.release()
    }
}
```

```kotlin
// MainActivity.kt — 删掉 lazy 创建，从 Application 取
private val app: MagicSushiApp get() = application as MagicSushiApp
private val prefsRepo: PrefsRepository get() = app.prefsRepo
private val soundPlayer: SoundPlayer get() = app.soundPlayer
```

**方案 B（轻量）**：让 `SoundPlayer` 支持 `recreate()`，在 `MainActivity` 每次重建时通知 ViewModel 更新引用。

**验收**：旋转屏幕后，游戏静音/音效仍正常，logcat 无 `play() on released SoundPool` 警告。

---

### 3. 动画协程不可取消（Pause 时仍在跑）

**根因**：
- `timerJob` 在 `onPause()` 中被取消 ✅
- 但 `onSwapAttempt()` 中的 `viewModelScope.launch { ... }` 没有保存为 `Job`
- 暂停时动画仍在跑，`delay()` 继续执行，`_state.update` 继续推送
- 恢复后 `swapProcessing` 可能仍为 `true`，游戏卡死

**方案**：保存 `swapJob`，在 `onPause()` 中取消。

**变更文件**：`viewmodel/GameViewModel.kt`

```kotlin
// 新增字段
private var swapJob: Job? = null

// 修改 onSwapAttempt
private fun onSwapAttempt(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
    swapJob?.cancel()  // 取消上一次的（如果有）
    swapJob = viewModelScope.launch {
        // ... 现有逻辑 ...
    }
}

// 修改 onPause，同时取消 swapJob
fun onPause() {
    _state.update { it.copy(phase = GamePhase.PAUSED) }
    timerJob?.cancel()
    swapJob?.cancel()  // ← 新增
}

// 在 swap 协程中，每次 delay 后检查 phase（可选增强）
// 在动画循环中添加：
for ((roundIdx, cascadeRound) in cascadeResult.cascades.withIndex()) {
    if (_state.value.phase != GamePhase.PLAYING) break  // ← 被暂停/结束了就跳出
    // ... 播放本 round 动画 ...
}
```

**验收**：动画播放中按暂停 → 动画立即停止 → 恢复后 `swapProcessing` 为 `false`，棋盘状态正确。

---

## 🟡 P1 — 应修复（架构/可维护性）

### 4. ViewModel 分解 —— 抽出动画编排器

> ✅ **已完成**（`9b96698`），但**形态与下面不同**：落地为 `engine/CascadeAnimator.kt`
> 里的 **`suspend` 顶层函数 `playCascadeAnimation`**，不是持有 scope/job 的 class ——
> 这样时序可用 `runTest` 虚拟时钟精确断言，取消由 suspend 天然传播。
> ⚠️ 下面示例里的 `if (roundIdx < cascadeResult.cascades.size - 1) delay(gapMs)`
> **有缺陷**：提前中止时会多等一个 gap。实际实现把间隙移到了下一轮开头。
> 附带行为变化：单轮 600ms → 500ms。详见文档开头。

**根因**：`GameViewModel` 600 行，同时承担动画编排、业务逻辑、生命周期、音效触发等 7 种职责。动画时序（3 帧 × 100ms delay × N 轮 cascade）硬编码在 `onSwapAttempt` 中，不可测试。

**方案**：抽出 `CascadeAnimator` 类，负责接收 `CascadeResult` 并按时间线推送 `AnimFrame`。

**新增文件**：`engine/CascadeAnimator.kt`

```kotlin
/**
 * CascadeAnimator — 按时间线播放 cascade 动画帧。
 *
 * 职责：接收 [CascadeResult]，按 3 帧 × 100ms + 间歇 100ms 的时序，
 * 逐帧回调 [onFrame]。
 *
 * 可被 ViewModel 调用，也可被单元测试独立测试。
 */
class CascadeAnimator(
    private val onFrame: (AnimFrame, Board) -> Unit,  // (animFrame, currentBoard) → emit 给 UI
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun play(
        newBoard: Board,
        cascadeResult: CascadeResult,
        onComplete: () -> Unit,
    ) {
        job?.cancel()
        job = scope.launch {
            var currentAnimBoard = newBoard
            for ((roundIdx, cascadeRound) in cascadeResult.cascades.withIndex()) {
                if (!isActive) break  // 被取消时退出
                val frames = AnimationEngine.generateFrames(currentAnimBoard, cascadeRound)

                // 帧 0: Fade Out
                onFrame(frames[0], currentAnimBoard)
                delay(ANIM_PHASE_MS)
                delay(ANIM_GAP_MS)
                // 帧 1: Fall
                onFrame(frames[1], currentAnimBoard)
                delay(ANIM_PHASE_MS)
                delay(ANIM_GAP_MS)
                // 帧 2: Spawn In
                onFrame(frames[2], currentAnimBoard)
                delay(ANIM_PHASE_MS)

                // 计算下一轮起始 board（gravity 后的结果）
                currentAnimBoard = GravityEngine.applyGravity(currentAnimBoard, cascadeRound)

                if (roundIdx < cascadeResult.cascades.size - 1) {
                    delay(ANIM_GAP_MS)
                }
            }
            // 动画结束，清除 animFrame
            onFrame(null, cascadeResult.finalBoard)
            onComplete()
        }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        const val ANIM_PHASE_MS = 100L
        const val ANIM_GAP_MS = 100L
    }
}
```

**变更文件**：`viewmodel/GameViewModel.kt`

```kotlin
// 在 GameViewModel 中
private val cascadeAnimator = CascadeAnimator(
    onFrame = { animFrame, animBoard ->
        _state.update { it.copy(
            animFrame = animFrame,
            board = if (animFrame == null) animBoard else it.board,
            // 只在最后一帧写入最终状态
        )}
    },
    scope = viewModelScope,
)

// onSwapAttempt 中代替手动循环
// 移除: for ((roundIdx, cascadeRound) in cascadeResult.cascades.withIndex()) { ... }
// 替换为:
cascadeAnimator.play(newBoard, cascadeResult) {
    // 动画结束时更新 score / combo / timer / unlock
    _state.update {
        // ... 同原来第 526-536 行的逻辑 ...
    }
    swapProcessing = false
}
```

**验收**：动画行为不变（视觉上无差异），`CascadeAnimator` 可独立单元测试，`onPause()` 调用 `cascadeAnimator.cancel()` 可中断动画。

---

### 5. 双重重力计算

**根因**：每轮 cascade 中，`AnimationEngine.generateFrames()` 内部调用了一次 `GravityEngine.applyGravity()`（用于计算 Fall 和 Spawn 帧），紧接着 ViewModel 循环中又调了一次。

**方案**：让 `AnimationEngine.generateFrames` 接收已经计算好的 post-gravity board，不再自己调 gravity。

**变更文件**：`engine/AnimationEngine.kt`、`engine/CascadeAnimator.kt`

```kotlin
// AnimationEngine.kt 修改签名
fun generateFrames(
    board: Board,               // 当前 board（含即将被消除的 tile）
    matches: List<Match>,
    fallenBoard: Board,         // 外部已算好的 gravity 后 board
): List<AnimFrame>
```

```kotlin
// CascadeAnimator.kt 中配合修改
// 在循环中先算 gravity，再传两个 board 给 generateFrames
val fallenBoard = GravityEngine.applyGravity(currentAnimBoard, cascadeRound, doRefill = false)
val frames = AnimationEngine.generateFrames(currentAnimBoard, cascadeRound, fallenBoard)
```

**验收**：在 `AnimationEngine.generateFrames` 入口处打 log，确认 `GravityEngine.applyGravity` 只被调用一次（在 CascadeAnimator 中），而非两次。

---

### 6. GravityEngine 与 spawnRefill 职责分离

> ✅ **已完成**（`6efaf19`），但**做法比下面更彻底**：不是"分离职责"，
> 而是直接**删掉 `doRefill` 与 `rng` 两个参数**。
> 关键发现：`CascadeEngine` 靠**默认值**隐式依赖它完成 cascade 连锁，
> `grep "doRefill"` 找不到该调用方，差点被当死代码删除。详见文档开头。

**根因**：`GravityEngine.applyGravity` 的参数 `doRefill: Boolean = true` 让它默认会调用 `BoardEngine.spawnRefill`。gravity 不应该知道 spawn 的存在。

**方案**：移除 `doRefill` 参数，永远只做 gravity。spawnRefill 由调用者显式调用。

**变更文件**：`engine/GravityEngine.kt`

```kotlin
// 修改前
fun applyGravity(board: Board, eliminatedMatches: List<Match>, doRefill: Boolean = true): Board {
    // ...
    return if (doRefill) {
        BoardEngine.spawnRefill(board.copy(grid = newGrid))
    } else {
        board.copy(grid = newGrid)
    }
}

// 修改后
fun applyGravity(board: Board, eliminatedMatches: List<Match>): Board {
    // ...
    return board.copy(grid = newGrid)  // 永远只做 gravity
}
```

**变更文件**：`engine/CascadeEngine.kt`

```kotlin
// 原来 cascadeUntilStable 中调用 applyGravity 默认 doRefill=true
// 改为：
currentBoard = GravityEngine.applyGravity(currentBoard, currentMatches)
currentBoard = BoardEngine.spawnRefill(currentBoard)  // 显式 spawn
```

**验收**：`GravityEngine` 中不再有 `import top.windyvalley.magicsushi.engine.BoardEngine`，`BoardEngineTest` 和 `GravityEngineTest` 全部通过。

---

### 7. GameCanvas 渲染分支重复

**根因**：`animFrame != null` 和 `else` 两个分支的 `SushiTile` 调用、手势处理逻辑完全一致，只有数据源和 key 不同。

**方案**：抽出一个统一的数据源迭代器。

**变更文件**：`ui/canvas/GameCanvas.kt`

```kotlin
// 新增一个内部数据类
private data class TileSlot(
    val key: Any,
    val row: Int,
    val col: Int,
    val type: SushiType,
    val tileAnim: AnimationEngine.TileAnim?,
    val isSelected: Boolean,
)

// 在 GameCanvas 中，将两个分支合并为一个
val tileSlots: Iterable<TileSlot> = remember(board, animFrame) {
    if (animFrame != null) {
        animFrame.map { (cellKey, renderState) ->
            TileSlot(
                key = renderState.visualId,
                row = cellKey.row,
                col = cellKey.col,
                type = renderState.type,
                tileAnim = renderState.anim,
                isSelected = selectedTile == cellKey.row to cellKey.col,
            )
        }
    } else {
        sequence {
            for (row in 0 until board.size) {
                for (col in 0 until board.size) {
                    val tile = board.grid[row][col] ?: continue
                    yield(TileSlot(
                        key = tile.id,
                        row = row,
                        col = col,
                        type = tile.type,
                        tileAnim = null,
                        isSelected = selectedTile == row to col,
                    ))
                }
            }
        }.asIterable()
    }
}

// 单次循环渲染
for (slot in tileSlots) {
    key(slot.key) {
        SushiTile(
            type = slot.type,
            cellSizePx = cellSizePx,
            isSelected = slot.isSelected,
            isDragging = draggingTile == slot.row to slot.col,
            tileAnim = slot.tileAnim,
            // ... 手势回调不变 ...
        )
    }
}
```

**验收**：视觉无变化，`GameCanvas.kt` 减少了约 40 行重复代码。

---

## 🟢 P2 — 建议修复（代码卫生/未来扩展）

### 8. MatchEngine 临时数组分配

> ✅ **已完成**（`b0eb294`，由 D3 换 `List` 时顺手带走）。
> ⚠️ **下面的索引器方案未被采用，别照着再改一遍。**
> 实际做法：`Array` → `List`，中间容器保留，理由见文档开头「与原方案的
> 四处偏差」。`grep "Array(" MatchEngine.kt` 已无结果，目的已达成。

**根因**：列扫描时每次分配 `Array(7){ ... }`，7×7 棋盘上每次 `detectMatches` 有 7 次分配。

**方案**：将 `detectLineMatches` 改为接受 `(Int) -> SushiTile?` 索引器，或直接内联逻辑。

**变更文件**：`engine/MatchEngine.kt`

```kotlin
// 修改签名
private fun detectLineMatches(
    indexer: (Int) -> SushiTile?,
    lineLength: Int,
    axis: MatchAxis,
): List<Match> {
    // 用 indexer(i) 替代 line[i]，不再需要数组
    // ... 其余逻辑不变 ...
}

// 调用处
// 水平：detectLineMatches({ col -> board.grid[row][col] }, board.size, HORIZONTAL)
// 垂直：detectLineMatches({ row -> board.grid[row][col] }, board.size, VERTICAL)
```

**验收**：`MatchEngine.detectMatches` 中不再有 `Array(board.size) { ... }` 分配，`MatchEngineTest` 全部通过。

---

### 9. 清理源文件中残留的 `fun main()` 手动测试

> ✅ **已完成**（`79418fc`）。共删 **1763 行**，8 个引擎文件。
> ⚠️ 下面「注意」那条真的踩到了：`Models.kt` 的 8 条断言**没有任何 JUnit
> 对应**（其余 7 个引擎都有）。先新建 `ModelsTest.kt`（6 例）迁移跑绿，
> 再删源文件。顺带清掉了 `MatchEngine` 的冗余 initializer 告警 ——
> 那个 `nextId` 本来就在 `main()` 块里。

**根因**：每个 Engine 文件末尾有一个 `fun main()` 手动测试入口，这是早期 TDD 的遗留产物。现在已有完整的 JUnit 测试（`AnimationEngineTest.kt` 等），这些 `main()` 是冗余的。

**方案**：删除所有 Engine 文件末尾的 `fun main()` 块。

**涉及文件**：
- `engine/Models.kt`（第 217-248 行）
- `engine/GameState.kt`（第 248-347 行）
- `engine/GravityEngine.kt`（第 196-718 行，整个 manual test 区域）
- `engine/MatchEngine.kt`（第 184-452 行）
- `engine/BoardEngine.kt`（第 256-470 行）
- `engine/CascadeEngine.kt`（第 241-596 行）
- 其他有 `fun main()` 的文件

**注意**：删除前确认对应的 JUnit 测试已覆盖相同场景。如果某些场景只有 `main()` 测试覆盖，先迁移到 JUnit 再删除。

**验收**：所有 Engine 文件末尾不再有 `fun main()`，`./gradlew test` 通过。

---

### 10. `MagicSushiApp` 作为真正的 DI 入口

**根因**：`MagicSushiApp` 被声明为 "预留给后续 DI"，但实际是一个空壳。依赖（`PrefsRepository`、`SoundPlayer`）在 `MainActivity` 中创建，导致生命周期问题（见 P0-2）。

**方案**：已在 **P0-2 修复方案 A** 中覆盖。`MagicSushiApp` 接管依赖创建和生命周期管理。

**变更文件**：`MagicSushiApp.kt`、`MainActivity.kt`、`GameViewModelFactory.kt`

```kotlin
// GameViewModelFactory.kt 不变，但创建方式改为：
// 从 Activity 中
val factory = GameViewModelFactory(app.prefsRepo, app.soundPlayer)
```

---

## 实施顺序

> ✅ P0/P1/P2 全部完成。详见文档开头「实施结算」。

| 顺序 | 问题 | 预计工作量 | 影响范围 |
|------|------|-----------|---------|
| 1 | P0-1: swapProcessing 异常安全 | 10 分钟 | 1 个文件 |
| 2 | P0-2: SoundPlayer 生命周期 | 30 分钟 | 3 个文件 |
| 3 | P0-3: 动画协程可取消 | 15 分钟 | 1 个文件 |
| 4 | 🟡 P1-1: ViewModel 分解（CascadeAnimator 提取） | 2 小时 | 2 个文件（+1 新建） |
| 5 | 🟡 P1-2: 双重重力计算 | 1 小时 | 2 个文件 |
| 6 | 🟡 P1-3: GravityEngine 分离 | 1 小时 | 3 个文件 |
| 7 | 🟡 P1-4: GameCanvas 分支合并 | 30 分钟 | 1 个文件 |
| 8 | 🟢 P2-1: MatchEngine 分配优化 | 30 分钟 | 1 个文件 |
| 9 | 🟢 P2-2: 清理 main() 测试 | 15 分钟 | 7 个文件 |
| 10 | 🟢 P2-3: Application DI 入口 | 已在 P0-2 中覆盖 | — |

**推荐批次**：
- **批次 1（P0）**：问题 1-3 → 立即修复，1 小时内完成
- **批次 2（P1）**：问题 4-7 → 下次迭代，2-3 天
- **批次 3（P2）**：问题 8-10 → 有空时处理，1 天

---
---

# 第二部分：数据层修复方案

数据层问题独立编号为 D1-D8。其中 **D1、D2 是已确认的真实 bug**（不是设计气味），
优先级高于第一部分的 P1。

---

## 🔴 D1 — Tile id 复用导致 Compose 同级重复 key（真实 bug）

**根因**：`BoardEngine` 的两个地方用了**同一个 id 公式**：

```kotlin
// generateInitialBoard（第 86 行）
id = row * BOARD_SIZE + col

// spawnRefill（第 162 行）—— 同样的公式！
id = row * BOARD_SIZE + col
```

`spawnRefill` 只按「当前空位的坐标」算 id，完全不知道棋盘上已有哪些 id。
于是重力让老 tile 换位之后，新 tile 会拿到一个**已经被占用的 id**：

```
初始：      (0,0) id=0        (3,0) id=21
消除 (3,0)：(0,0) id=0        (3,0) null
重力后：    (0,0) null        (3,0) id=0      ← id=0 掉到了 row3
spawnRefill：(0,0) id=0  ←←←  (3,0) id=0      ← 同一块棋盘上出现两个 id=0
```

而 `GameCanvas` 非动画分支正是用 `tile.id` 当 Compose key：

```kotlin
key(tile.id) { SushiTile(...) }   // 两个兄弟节点拿到相同 key
```

**后果**：同级 `key()` 重复会让 Compose 复用错误的 slot —— `SushiTile` 内部的
`remember { dragOffset }`、`animateFloatAsState` 都会串到另一个格子上，表现为
「拖动/选中高亮跳到别的寿司上」「动画位移错位」。若将来把棋盘换成 `LazyVerticalGrid`，
重复 key 会直接抛 `IllegalArgumentException`。

注：`AnimationEngine` 里的 spawn tile 用的是**负 id**（`-1, -2, ...`），
动画分支本身是安全的；问题出在动画结束后写入的 `finalBoard`（来自 `spawnRefill`）。

**方案**：引入全局单调 id 生成器，让 tile id 在整个进程生命周期内唯一。

**新增文件**：`engine/TileIdGenerator.kt`

```kotlin
package top.windyvalley.magicsushi.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局唯一 tile id 生成器。
 *
 * tile id 的唯一职责是充当 Compose 的 `key()`，因此必须在
 * **同一块棋盘内绝对不重复**。历史实现用 `row * 7 + col` 推导 id，
 * 在 spawnRefill 之后会与重力换位后的老 tile 撞号（见 FIX_PLAN D1）。
 *
 * 单调递增 + AtomicInteger：
 * - 保证跨轮次、跨 cascade、跨整局都不重复
 * - 线程安全（引擎目前单线程，但零成本）
 * - 负数区间留给 AnimationEngine 的临时 spawn 帧（`-1, -2, ...`），互不冲突
 */
object TileIdGenerator {
    private val counter = AtomicInteger(0)

    fun next(): Int = counter.incrementAndGet()

    /** 仅供单元测试重置，保证用例之间互不影响。 */
    fun resetForTest() = counter.set(0)
}
```

**变更文件**：`engine/BoardEngine.kt`

```kotlin
// generateInitialBoard —— 第 86 行
- id = row * BOARD_SIZE + col,
+ id = TileIdGenerator.next(),

// fallbackNoMatchBoard —— 第 117 行
- id = row * BOARD_SIZE + col,
+ id = TileIdGenerator.next(),

// spawnRefill —— 第 162 行
- id = row * BOARD_SIZE + col,
+ id = TileIdGenerator.next(),
```

同时删掉 `spawnRefill` 文档里那段已经过时（且不成立）的说明：

```
- * Id allocation: `row * BOARD_SIZE + col` matches [generateInitialBoard]
- * so ids stay unique within a board generation. (Tiles from different
- * rounds may share ids across the game's lifetime ...)
+ * Id allocation: 由 [TileIdGenerator] 全局单调分配，保证同一棋盘内
+ * （以及整局游戏内）绝不重复 —— Compose `key()` 依赖这一点。
```

**验收**：
- 新增测试 `BoardEngineTest.spawnRefill 不产生重复 id`：构造一个消除后的
  board，调 `applyGravity` + `spawnRefill`，断言
  `grid.flatten().filterNotNull().map { it.id }.toSet().size == 49`。
- 手动验证：连续 cascade 若干轮后拖动棋子，高亮/位移不再跳格。

---

## 🔴 D2 — 一次性信号建模成持续字段，导致奖励飘字漏播（真实 bug）

**根因**：`lastRewardSeconds` 被当成「持续值」存在 `GameState` 里，
而 ViewModel 刻意**只在 reward > 0 时才更新**：

```kotlin
// GameViewModel 第 534 行
lastRewardSeconds = if (reward > 0) reward else it.lastRewardSeconds,
```

`GameState.kt` 的注释还把这条写成了有意设计（"避免 0 覆盖之前的非零值"）。
但消费端是靠**值变化**来触发动画的：

```kotlin
// RewardOverlay 第 73 行
LaunchedEffect(lastRewardSeconds) {   // key 没变 → effect 不重启
    if (lastRewardSeconds != null && lastRewardSeconds > 0) { ... }
}
```

**后果**：连续两次消除都奖励 +5s 时，`lastRewardSeconds` 从 `5` 变成 `5`
—— 值没变，`LaunchedEffect` 不重启，**第二次的 `+5s` 飘字根本不显示**。
而 `+5s` 恰恰是这个游戏最常见的奖励值，所以这不是边角 case，是高频漏播。

`isRollback` 是同一类问题的另一面：它靠 `false → true → false` 的**瞬时翻转**
传递信号，一旦 UI 因为别的字段变化而错过那 150ms 窗口，回弹动画就丢了。
`isNewRecord` 同理。

**方案**：把一次性信号从「状态」里拆出来，改用事件流。状态描述「现在是什么」，
事件描述「刚刚发生了什么」—— 二者不该混在一个 data class 里。

**新增文件**：`engine/GameEvent.kt`

```kotlin
package top.windyvalley.magicsushi.engine

/**
 * 一次性游戏事件（transient signal）。
 *
 * 与 [GameState] 的区别：
 * - [GameState] 回答"现在棋盘/分数/阶段是什么"，可重复读取，幂等。
 * - [GameEvent] 回答"刚刚发生了什么"，**只应被消费一次**。
 *
 * 为什么不塞进 GameState：同值连续触发（例如连续两次 +5s 奖励）在
 * data class 里表现为"字段没变化"，下游 `LaunchedEffect(key)` 不会重启，
 * 信号就丢了（见 FIX_PLAN D2）。事件流没有这个问题。
 */
sealed interface GameEvent {
    /** 消除获得时间奖励，[seconds] > 0。驱动 `+Ns` 飘字。 */
    data class TimeReward(val seconds: Int) : GameEvent

    /** 交换无效，棋盘即将弹回。驱动抖动/红闪。 */
    data object SwapRejected : GameEvent

    /** 本局打破最高分。驱动 GameOverDialog 的庆祝效果。 */
    data class NewRecord(val score: Int) : GameEvent
}
```

**变更文件**：`viewmodel/GameViewModel.kt`

```kotlin
// 新增事件流（extraBufferCapacity 保证 tryEmit 不丢事件）
private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)
val events: SharedFlow<GameEvent> = _events.asSharedFlow()

// 奖励：改为发事件，不再写 state 字段
- lastRewardSeconds = if (reward > 0) reward else it.lastRewardSeconds,
// （从 _state.update 中删除该行）
+ if (reward > 0) _events.tryEmit(GameEvent.TimeReward(reward))

// 无效交换：保留 isRollback 供棋盘渲染用（它确实需要"当前正在弹回"这个状态），
// 但额外发一次事件供动画/震动消费
  _state.update { it.copy(board = swappedBoard, selectedTile = null, isRollback = true) }
+ _events.tryEmit(GameEvent.SwapRejected)

// 破纪录
  if (isNew) prefsRepo.saveHighScore(finalScore)
+ if (isNew) _events.tryEmit(GameEvent.NewRecord(finalScore))
```

**变更文件**：`ui/screen/RewardOverlay.kt` —— 改为消费事件

```kotlin
@Composable
fun RewardOverlay(
    events: SharedFlow<GameEvent>,       // ← 替换 lastRewardSeconds: Int?
    modifier: Modifier = Modifier,
) {
    var showReward by remember { mutableStateOf(false) }
    var rewardValue by remember { mutableIntStateOf(0) }

    // 关键：collect 事件流，同值连续触发也会各自走一遍
    LaunchedEffect(Unit) {
        events.filterIsInstance<GameEvent.TimeReward>().collect { ev ->
            rewardValue = ev.seconds
            showReward = true
            delay(1500)
            showReward = false
        }
    }
    // ... Box / AnimatedVisibility 部分不变 ...
}
```

**变更文件**：`ui/screen/GameScreen.kt`

```kotlin
- RewardOverlay(lastRewardSeconds = state.lastRewardSeconds)
+ RewardOverlay(events = viewModel.events)

// TimerDisplay 若也用 lastRewardSeconds 做飘字，一并改为消费事件；
// 只作纯倒计时展示的话直接删掉该入参。
```

**变更文件**：`engine/GameState.kt` —— 删除 `lastRewardSeconds` 字段及其冗长注释，
并把 `isNewRecord` 标注为「GameOverDialog 的展示态」而非信号。

**注意**：连续两次奖励值相同时，`showReward` 会被第二次事件重新置 `true`，
但上一次的 `delay(1500)` 已随 `collect` 的顺序执行结束，不会互相打断；
若希望第二次飘字重新计时，当前写法已经满足。

**验收**：
- 连续两次触发 +5s 奖励 → 飘字显示两次（回归 bug）。
- 新增 VM 测试：`runTest` 中收集 `events`，断言连续两次同值奖励产出 2 个
  `TimeReward(5)` 事件。

---

## 🟡 D3 — `Board.grid` 用 `Array<Array<SushiTile?>>`：破坏 equals + Compose 不稳定

**根因**：

```kotlin
data class Board(
    val grid: Array<Array<SushiTile?>> = ...,
)
```

两个后果：

1. **equals 语义错**：Kotlin `Array.equals` 是引用比较，所以内容完全相同的两个
   `Board` 也 `!=`。连带 `GameState` 的 `equals` 也不可信 —— 测试里想断言
   「状态未变」写不出来，`distinctUntilChanged` 之类的优化也全部失效。
2. **Compose 不稳定（更实际的影响）**：`Array` 不是 Compose 的 stable 类型，
   `Board` / `GameState` 因此被推断为 unstable，`GameCanvas` **无法跳过重组** ——
   动画期间每 100ms 一次的 state emit 会让整块棋盘 49 个 `SushiTile` 全量重组，
   而不是只重组真正变化的格子。

**方案**：换成 `List<List<SushiTile?>>` 并标注 `@Immutable`。`List` 是结构化
equals，且 Compose 视 `kotlinx.collections.immutable` / 只读 `List` 为稳定。

**变更文件**：`engine/Models.kt`

```kotlin
+ import androidx.compose.runtime.Immutable   // 若坚持 engine 层零 Android 依赖，
                                             // 见下方"取舍"

@Immutable
data class Board(
    val size: Int = 7,
-   val grid: Array<Array<SushiTile?>> = Array(size) { arrayOfNulls<SushiTile>(size) },
+   val grid: List<List<SushiTile?>> = List(size) { List(size) { null } },
    val swapLock: Boolean = false,
    val cascadeLock: Boolean = false,
)
```

**取舍**：`@Immutable` 来自 `androidx.compose.runtime`，会打破「engine 层零
Android 依赖」这条纪律。两个选择：
- **(a)** 引入 `compose-runtime`（纯 Kotlin artifact，不含 android framework，
  JVM 单测仍可跑）—— 推荐，收益直接。
- **(b)** 不加注解，只换 `List`。`List` 本身已是 stable，Compose 能正确推断，
  也能拿到大部分收益，engine 保持纯净。

**连带改动**（机械但涉及面广，建议独立一个 commit）：
- `GravityEngine`：`board.grid[row].clone()` → 用 `map { it.toMutableList() }`
  构建，最后 `map { it.toList() }` 冻结。
- `BoardEngine.spawnRefill` / `attemptSwap`：同上，改为构建可变 List 再冻结。
- `MatchEngine`：`Array(board.size) { r -> board.grid[r][col] }` 一并按 D7 优化掉。
- `AnimationEngine`：只读访问，无需改动。
- 各 `*Test.kt` 中手工构造 grid 的地方：`Array(7){ Array(7){...} }` → `List(7){ List(7){...} }`。

**验收**：`./gradlew test` 全绿；`Board(...) == Board(...)` 内容相同即为 true；
Compose compiler metrics 中 `Board` / `GameState` 不再标记 unstable。

---

## 🟡 D4 — `animFrame` 混在 `GameState` 里，状态自相矛盾

> ✅ **已完成**，拆成两半：
> - `468dd63` **索引体系收口**（原方案没有这一项）—— 废除 `visualId` 负数
>   编号空间，`tile.id` 成为全 App 唯一身份。**修掉了真机报告的手势错位 bug**。
> - `6a3957b` **`BoardPresentation` 密封类型** —— 即下面的方案内容。
>
> 与下面示例的差异：`presentation` 实现为 `GameState` 的 **computed property**
> （由 `board` + `animFrame` 派生），`animFrame` 保留为存储形态供 `copy()` 写入，
> 而非把字段替换掉 —— 这样 VM 侧的写入点不用全改。
> 已验证密封类型真的生效：注入探针分支后编译器在 `GameCanvas` 两处点名报错。

**根因**：动画期间 `board` 被刻意冻结，视觉真相在 `animFrame` 里，两个字段描述
同一件事的不同版本：

```kotlin
data class GameState(
    val board: Board,           // 动画期间不变（还含着已被消除的 tile）
    val animFrame: AnimFrame?,  // 每 100ms 一变，与 board 不同步
)
```

UI 必须靠一条隐式约定「`animFrame != null` 时忽略 `board`」才能正确渲染 ——
这条约定只写在注释里，编译器不管，新人改 UI 时极易踩空。

**方案**：把「棋盘该怎么渲染」收敛成一个密封类型，让不一致在类型层面消失。

**新增到** `engine/GameState.kt`：

```kotlin
/**
 * 棋盘渲染态。用密封类型取代 (board, animFrame?) 这对隐式互斥字段：
 * 编译器强制 UI 处理全部分支，不再依赖"animFrame 非空时忽略 board"的口头约定。
 */
sealed interface BoardPresentation {
    /** 稳定态：直接渲染 [board]。 */
    data class Stable(val board: Board) : BoardPresentation

    /**
     * 动画态：渲染 [frame]。[logicalBoard] 仅供手势命中测试等逻辑用途，
     * **不应**用于绘制。
     */
    data class Animating(
        val frame: AnimFrame,
        val logicalBoard: Board,
    ) : BoardPresentation
}
```

```kotlin
data class GameState(
-   val board: Board = BoardEngine.generateInitialBoard(),
-   val animFrame: AnimFrame? = null,
+   val presentation: BoardPresentation = BoardPresentation.Stable(Board()),
    // ... 其余字段不变 ...
) {
    /** 逻辑棋盘：手势命中、相邻判定等一律走这里。 */
    val board: Board get() = when (val p = presentation) {
        is BoardPresentation.Stable -> p.board
        is BoardPresentation.Animating -> p.logicalBoard
    }
}
```

**变更文件**：`ui/canvas/GameCanvas.kt` —— 入参从 `(board, animFrame)`
收成单个 `presentation: BoardPresentation`，配合 P1-4（分支合并）一次做完：

```kotlin
val tileSlots = when (val p = presentation) {
    is BoardPresentation.Animating -> p.frame.map { (k, rs) -> TileSlot(...) }
    is BoardPresentation.Stable    -> p.board.toTileSlots()
}
```

**依赖关系**：本项与 **P1-4（GameCanvas 分支合并）** 和 **P1-1（CascadeAnimator）**
改的是同一批代码，**建议三者合并为一次重构**，避免同一处文件改三遍。

**验收**：`GameState` 不再同时存在 `board` + `animFrame` 两个可写字段；
UI 侧 `when` 覆盖两个分支（无 `else`）即可编译通过。

---

## 🟡 D5 — 静音状态存在三份拷贝

**根因**：同一个 `muted` 布尔值被三个组件各存一份：

| 位置 | 形态 |
|------|------|
| `PrefsRepository` | SharedPreferences + `mutedFlow: StateFlow<Boolean>` |
| `SoundPlayer` | `_mutedFlow: MutableStateFlow<Boolean>` |
| `GameState` | `isMuted: Boolean` |

`toggleMute()` 得手工同步三处：

```kotlin
fun toggleMute() {
    val newMuted = !_state.value.isMuted
    prefsRepo.setMuted(newMuted)      // 1
    soundPlayer.setMuted(newMuted)    // 2
    _state.update { it.copy(isMuted = newMuted) }  // 3
}
```

漏掉任何一处就静默不一致。更糟的是 `PrefsRepository` 已经暴露了
`mutedFlow` / `highScoreFlow`，**但没有任何人 collect 它们** —— 白写的响应式
接口，实际走的是手工镜像。`highScore` 也是同样的双份结构。

**方案**：确立 `PrefsRepository` 为设置类状态的唯一数据源，其余两处改为派生。

**变更文件**：`audio/SoundPlayer.kt` —— 删掉自有的 muted 状态，改由外部注入判断

```kotlin
- private val _mutedFlow = MutableStateFlow(false)
- val mutedFlow = _mutedFlow.asStateFlow()
+ /** 由外部（VM/Repository）提供当前是否静音，SoundPlayer 不自持状态。 */
+ private var mutedProvider: () -> Boolean = { false }
+ fun bindMutedProvider(provider: () -> Boolean) { mutedProvider = provider }

  fun playSwap() {
-     if (_mutedFlow.value || !isLoaded) return
+     if (mutedProvider() || !isLoaded) return
      soundPool?.play(swapId, 1f, 1f, 1, 0, 1f)
  }
  // playMatch / playCombo / playTick 同样替换
- fun setMuted(muted: Boolean) { _mutedFlow.value = muted }
- fun isMuted(): Boolean = _mutedFlow.value
```

**变更文件**：`viewmodel/GameViewModel.kt` —— 让 `GameState` 的设置字段由
Prefs 的 Flow 驱动，而不是手工写入

```kotlin
init {
    soundPlayer.bindMutedProvider { prefsRepo.isMuted() }

    // 设置类状态单向流入 GameState，toggleMute 只需写 Repository
    viewModelScope.launch {
        prefsRepo.mutedFlow.collect { muted ->
            _state.update { it.copy(isMuted = muted) }
        }
    }
    viewModelScope.launch {
        prefsRepo.highScoreFlow.collect { hs ->
            _state.update { it.copy(highScore = hs) }
        }
    }
    startGame()
}

fun toggleMute() {
    prefsRepo.setMuted(!prefsRepo.isMuted())   // 只写一处，其余自动跟随
}
```

**顺带修掉**：`startGame()` 里 `isMuted = prefsRepo.isMuted()` /
`highScore = prefsRepo.getHighScore()` 的手工回填可以删除（已由 collect 保证）。

**验收**：`toggleMute()` 只调用一次 `prefsRepo`；切静音后音效立即停止、
图标同步、杀进程重进仍保持；`grep -rn "setMuted" app/src/main` 只剩 Repository 一处。

---

## 🟡 D6 — `GameState` 默认值里跑 RNG，且初始棋盘被生成两次

**根因**：

```kotlin
// GameState.kt 第 198 行
val board: Board = BoardEngine.generateInitialBoard(),   // 默认值里做重活 + 摸 RNG
```

```kotlin
// GameViewModel 第 152 行：构造 _state 时走默认值 → 生成棋盘 #1
private val _state = MutableStateFlow(GameState(isMuted = ..., highScore = ...))

// 紧接着 init { startGame() } → 又生成棋盘 #2，#1 直接被丢弃
```

data class 的默认参数里藏一次「拒绝采样循环 + 随机数」既浪费（每局启动白算一副
49 格棋盘），也让 `GameState()` 变成不纯的构造 —— 测试里想拿一个确定性初始状态
必须显式传 board，很容易忘。

**方案**：默认值改为空棋盘，真正的初始棋盘只由 `startGame()` 生成。

**变更文件**：`engine/GameState.kt`

```kotlin
- val board: Board = BoardEngine.generateInitialBoard(),
+ /** 默认空棋盘（全 null）。真实棋盘由 GameViewModel.startGame() 注入。 */
+ val board: Board = Board(),
```

（若已实施 D4，则改为 `presentation = BoardPresentation.Stable(Board())`。）

**变更文件**：`engine/GameState.kt` 的 manual test 段落里
`check(s0.board.grid.all { row -> row.all { it != null } })` 这类断言需相应调整
—— 或按 P2-2 直接删掉整个 `fun main()`。

**验收**：`GameState()` 不再触发 RNG；启动时 `generateInitialBoard` 只被调用一次
（可在其入口临时打 log 验证）。

---

## 🟢 D7 — `GameState` 上帝对象趋势（12 字段）

**根因**：`board / score / combo / remainingSeconds / phase / selectedTile /
isMuted / highScore / isRollback / isNewRecord / lastRewardSeconds / animFrame`
—— 逻辑状态、UI 交互态、持久化镜像、一次性信号、动画帧全挤在一个 data class 里。

执行完 D2（移出 `lastRewardSeconds`）、D4（`animFrame` 收进 `presentation`）、
D5（设置项单向流入）之后已经缓解不少。若后续要加道具/关卡/特殊方块，再按下面分组：

```kotlin
data class GameState(
    val presentation: BoardPresentation = BoardPresentation.Stable(Board()),
    val progress: RoundProgress = RoundProgress(),      // score / combo / remainingSeconds
    val interaction: InteractionState = InteractionState(),  // selectedTile / isRollback
    val settings: SettingsState = SettingsState(),      // isMuted / highScore
    val phase: GamePhase = GamePhase.IDLE,
)
```

**现在不必做** —— 字段数尚可控，过早拆分会让 `copy()` 链变长。等到加第一个
新玩法系统时再动，届时 D2/D4/D5 已经把最脏的部分清掉了。

---

## 🟢 D8 — `PrefsRepository` 主线程同步 IO

**根因**：`getSharedPreferences` 首次调用会读磁盘，而它发生在
`MainActivity` 的属性初始化 → ViewModel 构造 → `prefsRepo.getHighScore()`
这条**主线程**链路上。数据量极小（两个键），当前不会触发 ANR，属于潜在隐患。

**方案（可选）**：迁移到 `DataStore<Preferences>`，天然 Flow + 协程 IO；
或最低成本地把首次读取放到 `Dispatchers.IO` 预热。若坚持 SharedPreferences，
现状可接受，**不建议为此单独排期** —— 等有别的持久化需求（如关卡进度）时一起换。

---

## 数据层实施顺序

> ✅ D1-D6 全部完成，D7/D8 按原判断暂缓。详见文档开头「实施结算」。

| 顺序 | 问题 | 类型 | 工作量 | 影响文件 |
|------|------|------|--------|---------|
| 1 | **D1** tile id 复用 | 🔴 真实 bug | 30 分钟 | 2（+1 新建） |
| 2 | **D2** 奖励飘字漏播 | 🔴 真实 bug | 1.5 小时 | 4（+1 新建） |
| 3 | D6 默认值 RNG / 双重生成 | 🟡 浪费+不纯 | 15 分钟 | 1 |
| 4 | D5 静音三份拷贝 | 🟡 一致性 | 1 小时 | 3 |
| 5 | D3 `Array` → `List` + 稳定性 | 🟡 性能+语义 | 2-3 小时 | 6+ 及全部测试 |
| 6 | D4 `animFrame` → `BoardPresentation` | 🟡 类型安全 | 2 小时 | 3（与 P1-1/P1-4 合并做） |
| 7 | D7 GameState 拆分 | 🟢 暂缓 | — | 等新玩法再动 |
| 8 | D8 DataStore 迁移 | 🟢 暂缓 | — | 等新持久化需求 |

---

## 合并后的总实施建议

> ✅ **批次 1-4 全部执行完毕**（2026-08-06）。以下批次划分保留作历史记录，
> 实际执行情况与偏差见文档开头的「实施结算」。
>
> **实际执行顺序与原计划的差异**：
> - 批次 3 没有一次性做完，而是拆成 `D3 → P1-4 → P1-2 → D4前半 → P1-1 → P1-3`
>   逐个提交，每项单独跑测试 + 出 APK 真机验证。事后看这个选择是对的：
>   P1-4 合并渲染分支后**暴露出预存的手势错位 bug**（`pointerInput(type)`
>   捕获过期坐标），如果六项一起提交，根因定位会困难得多。
> - 批次 4（清理）挪到了 D4 后半之前做。先清掉 `GameState.kt` 里 131 行
>   `fun main()`，再改它的结构，改造面更小。
>
> **经验**：涉及渲染层/动画的重构，宁可多提交几次、每次出包验证，
> 也不要攒成一个大 commit —— 真机才能发现的问题需要能二分定位。

原第一部分的 P0/P1/P2 与数据层的 D1-D8 有重叠改动面，按下面批次走可以少改重复代码：

**批次 1 —— 止血（约 2.5 小时）**
`P0-1` 异常安全 → `P0-2` SoundPlayer 生命周期 → `P0-3` 动画可取消 →
`D1` tile id → `D2` 奖励飘字 → `D6` 默认值。
六项彼此独立、改动小，全部是「不改架构就能修掉的真实缺陷」。
建议每项一个 commit，方便回滚。

**批次 2 —— 一致性收口（约 1 小时）**
`D5` 静音单一数据源。独立、低风险，顺手把没人 collect 的 Flow 用起来。

**批次 3 —— 结构重构（约 6-8 小时，务必一起做）**
`P1-1` CascadeAnimator + `P1-2` 双重重力 + `P1-3` GravityEngine 分离 +
`P1-4` GameCanvas 合并 + `D3` Array→List + `D4` BoardPresentation。
这六项改的是同一批文件（ViewModel / GameCanvas / AnimationEngine / GravityEngine /
Models），分批做等于同一处改三遍。建议开独立分支，改完跑全量测试 + 真机验证动画。

**批次 4 —— 清理（约 1 小时）**
`P2-1` MatchEngine 分配（可在 D3 换 List 时顺手做掉）+ `P2-2` 删除 `fun main()`。

**暂缓**：`D7`（GameState 拆分）、`D8`（DataStore）—— 等下一个玩法需求触发。