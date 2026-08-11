# Magic Sushi 设计文档 v1.0

> **状态：** ✅ design sign-off
> **最后更新：** 2026-06-20 11:26
> **追溯：** eng-design skill 输出

---

## 1. 设计概述

### 1.1 项目

Magic Sushi（魔法寿司）Android 版本，受 MTK 原版三消游戏启发，玩法规则由
作者重新定义（非复刻，差异见 01-requirements.md §1.2）。

### 1.2 核心约束（来自需求）

| 约束 | 来源 |
|------|------|
| 单端 MVP（Android only） | 需求 Q2 |
| 纯 Kotlin（v1.0 不上 NDK） | 需求 Q2 |
| 包名 `top.windyvalley.magicsushi` | 沐风指定 |
| 棋盘 7×7 / 6 种寿司 | 需求 Q1 |
| 60 秒倒计时 | 需求 Q5 |
| 支持点击两次交换 + 拖动交换 | 需求 FR-2.1 + FR-2.2 |
| 完全离线可玩 | 需求 NFR-3.3 |

### 1.3 核心设计原则

1. **核心逻辑与 UI 完全解耦**：所有 Engine（BoardEngine/MatchEngine/CascadeEngine 等）是纯 Kotlin 无 Android 依赖
2. **状态驱动 UI**：ViewModel+StateFlow 推送不可变 `GameState`，Compose 自动重绘
3. **Canvas 负责渲染**：棋盘绘制用 `CanvasComposable`，动画用 Compose Animation API
4. **素材优先复用**：直接借用 EXL/Magic-Sushi 的 PNG 素材，不二次绘制

---

## 2. 架构概览

### 2.1 分层架构

```
┌──────────────────────────────────────┐
│           UI 层（Compose）            │
│  GameScreen / GameCanvas / Overlays  │
├──────────────────────────────────────┤
│         状态层（ViewModel）            │
│      GameViewModel + StateFlow       │
├──────────────────────────────────────┤
│       核心逻辑层（纯 Kotlin）          │
│ BoardEngine / MatchEngine / Cascade  │
│ GravityEngine / ScoreEngine          │
├──────────────────────────────────────┤
│        持久化层（SharedPrefs）        │
│         PrefsRepository              │
└──────────────────────────────────────┘
```

### 2.2 模块职责

| 模块 | 职责 | 依赖 |
|------|------|------|
| `GameViewModel` | UI 状态协调，接收用户事件，调用 Engine | 依赖所有 Engine + PrefsRepo |
| `BoardEngine` | 棋盘初始化、寿司交换、锁定管理 | 无 |
| `MatchEngine` | 三连检测（横/竖），支持 L/T 形 | 无 |
| `CascadeEngine` | 递归连锁检测 | 依赖 MatchEngine |
| `GravityEngine` | 重力下落填充（支持 doRefill 控制） | 无 |
| `ScoreEngine` | 计分（基础+连锁加成） | 无 |
| `TimerEngine` | 倒计时逻辑、重置奖励（v1.0.3：消除→重置到60s） | 无 |
| `AnimationEngine` | 级联三阶段动画帧生成（FadeOut/Fall/SpawnIn） | 依赖 GravityEngine |
| `PrefsRepository` | 最高分读写 | Android SharedPreferences |
| `SoundPlayer` | OGG 音效播放（swap/match/combo/tick） | Android SoundPool |
| `GameCanvas` | 棋盘绘制（CanvasComposable） | 依赖 GameState |
| `SushiTile` | Canvas 触控手势处理（点击/拖动） | 依赖 GameViewModel |
| `GameScreen` | Compose 主界面 | 依赖 GameViewModel |
| `ScoreOverlay` | 得分显示（数字滚动） | — |
| `TimerDisplay` | 倒计时显示（+5s 飘字） | — |
| `RewardOverlay` | +5s 奖励飘字动画 | — |
| `PauseDialog` | 暂停对话框 | — |
| `GameOverDialog` | 结束对话框 | — |

**核心枚举（Models.kt）：**
- `SushiType`（6 种寿司）
- `Direction`（4 方向，cardinal：UP/DOWN/LEFT/RIGHT）—— 用于手势、重力
- `MatchAxis`（2 轴向，axial：HORIZONTAL/VERTICAL）—— 用于 Match.axis

**GamePhase（engine/GameState.kt）：**
- `enum class GamePhase`：IDLE / PLAYING / PAUSED / GAME_OVER（v3：移入 GameState.kt，支持 onPause/onResume）

---

## 3. 核心算法

### 3.1 三连检测（MatchEngine）

```kotlin
object MatchEngine {
    /**
     * 检测棋盘上所有三连组合
     * @return List<Match> 所有匹配的列表
     */
    fun detectMatches(board: Board): List<Match> {
        val matches = mutableSetOf<Match>()
        // 横向检测
        for (row in 0 until board.size) {
            detectLineMatches(board.grid[row], MatchAxis.HORIZONTAL)?.let { matches.add(it) }
        }
        // 竖向检测
        for (col in 0 until board.size) {
            val column = board.grid.map { it[col] }.toTypedArray()
            detectLineMatches(column, MatchAxis.VERTICAL)?.let { matches.add(it) }
        }
        return matches.toList()
    }

    private fun detectLineMatches(
        line: Array<SushiTile?>,
        axis: MatchAxis
    ): Match? {
        var count = 1
        var start = 0
        val matches = mutableListOf<SushiTile>()

        for (i in 0 until line.size - 1) {
            if (line[i]?.type == line[i + 1]?.type) {
                count++
            } else {
                if (count >= 3) {
                    return Match(line.copyOfRange(start, start + count).filterNotNull().toList(), axis, count)
                }
                count = 1
                start = i + 1
            }
        }
        return if (count >= 3) Match(line.copyOfRange(start, start + count).filterNotNull().toList(), axis, count) else null
    }
}
```

### 3.2 初始填充（BoardEngine）

```kotlin
object BoardEngine {
    /**
     * 生成初始棋盘，确保无三连
     */
    fun generateInitialBoard(): Board {
        while (true) {
            val grid = Array(7) { row ->
                Array(7) { col ->
                    SushiTile(
                        id = (row * 7 + col),
                        type = SushiType.entries.random(),
                        row = row,
                        col = col
                    )
                }
            }
            val board = Board(grid = grid)
            if (MatchEngine.detectMatches(board).isEmpty()) {
                return board
            }
            // 死循环兜底：极低概率（7^49 分之一）才可能触发
        }
    }
}
```

### 3.3 重力下落（GravityEngine）

```kotlin
object GravityEngine {
    fun applyGravity(board: Board, eliminatedMatches: List<Match>): Board {
        // 1. 标记被消除的位置为 null
        val newGrid = board.grid.map { it.clone() }.toTypedArray()
        eliminatedMatches.flatMap { it.tiles }.forEach { tile ->
            newGrid[tile.row][tile.col] = null
        }

        // 2. 每列单独下落
        for (col in 0 until board.size) {
            val column = newGrid.map { it[col] }.toMutableList()
            // 下落：非null向前挤
            val filtered = column.filterNotNull().toMutableList()
            // 补 null 到顶部
            while (filtered.size < board.size) {
                filtered.add(0, null as SushiTile?)
            }
            // 写回
            filtered.forEachIndexed { row, tile ->
                newGrid[row][col] = tile?.copy(row = row, col = col)
            }
        }
        return Board(grid = newGrid)
    }
}
```

---

## 4. 关键设计决策（ADR 摘要）

| ADR | 决策 | 结论 |
|-----|------|------|
| **ADR-001** | 触控交互实现 | `PointerInputScope` + `detectDragGestures` ✅ |
| **ADR-002** | 状态管理方案 | `ViewModel` + `StateFlow` ✅ |
| **ADR-003** | 动画引擎 | Compose Animation API ✅ |
| **ADR-004** | 倒计时奖励机制 | 消除 → 重置到 60s（v1.0.3 修订）✅ |
| — | 棋盘渲染 | `CanvasComposable` ✅ |
| — | 素材格式 | PNG（EXL 项目直接复用）✅ |

详细 ADR 见：`02-adr/`

---

## 5. Android 工程结构

```
android-app/
├── app/
│   ├── src/main/
│   │   ├── java/top/windyvalley/magicsushi/
│   │   │   ├── MagicSushiApp.kt          # Application 类
│   │   │   ├── MainActivity.kt            # Activity（Compose 入口）
│   │   │   │
│   │   │   ├── engine/                   # 核心逻辑层（纯 Kotlin，无 Android 依赖）
│   │   │   │   ├── Models.kt             # Board / SushiTile / Match / Direction / MatchAxis
│   │   │   │   ├── GameState.kt          # GameState data class + GamePhase enum
│   │   │   │   ├── BoardEngine.kt
│   │   │   │   ├── MatchEngine.kt
│   │   │   │   ├── CascadeEngine.kt
│   │   │   │   ├── GravityEngine.kt
│   │   │   │   ├── ScoreEngine.kt
│   │   │   │   ├── TimerEngine.kt
│   │   │   │   └── AnimationEngine.kt     # 三阶段动画帧生成
│   │   │   │
│   │   │   ├── viewmodel/
│   │   │   │   ├── GameViewModel.kt      # 主 ViewModel
│   │   │   │   └── GameViewModelFactory.kt
│   │   │   │
│   │   │   ├── ui/
│   │   │   │   ├── theme/                # Compose Theme + Color + Type
│   │   │   │   ├── canvas/              # CanvasComposable 绘制
│   │   │   │   │   ├── GameCanvas.kt     # 7×7 棋盘画布
│   │   │   │   │   ├── SushiTile.kt      # 触控手势（点击/拖动）
│   │   │   │   │   └── SushiPainter.kt   # 寿司渲染
│   │   │   │   └── screen/              # Compose 界面组件
│   │   │   │       ├── GameScreen.kt     # 主游戏屏幕
│   │   │   │       ├── ScoreOverlay.kt    # 得分显示
│   │   │   │       ├── TimerDisplay.kt    # 倒计时 + +5s 飘字
│   │   │   │       ├── RewardOverlay.kt   # +5s 奖励提示
│   │   │   │       ├── PauseDialog.kt     # 暂停对话框
│   │   │   │       └── GameOverDialog.kt  # 结束对话框
│   │   │   │
│   │   │   ├── data/
│   │   │   │   └── PrefsRepository.kt   # SharedPreferences 封装
│   │   │   │
│   │   │   └── audio/
│   │   │       └── SoundPlayer.kt        # OGG 音效播放
│   │   │
│   │   ├── res/
│   │   │   ├── drawable/                 # 6 种寿司 PNG（来自 EXL）
│   │   │   ├── raw/                      # 4 个 OGG 音效
│   │   │   ├── mipmap-*/                 # 启动器图标
│   │   │   └── values/                   # strings / colors / themes
│   │   │
│   │   └── AndroidManifest.xml
│   │
│   ├── src/test/engine/                  # 单元测试（6 个测试文件，56 个用例）
│   │   ├── BoardEngineTest.kt
│   │   ├── MatchEngineTest.kt
│   │   ├── GravityEngineTest.kt
│   │   ├── CascadeEngineTest.kt
│   │   ├── ScoreEngineTest.kt
│   │   ├── TimerEngineTest.kt
│   │   └── AnimationEngineTest.kt        # 待补充
│   │
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── gradle/wrapper/
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties
```

---

## 6. 风险摘要

完整风险登记见 `02-risk.md`。

| 风险 | 影响 | 概率 | 状态 |
|------|------|------|------|
| R-001 EXL 素材 LICENSE | 法务 | M | monitoring |
| R-002 Canvas 性能 | 体验 | L | monitoring |
| R-003 初始填充死循环 | 功能 | L | monitoring |
| R-004 玩家刷小消除拖时间 | 体验 | L | monitoring |
| R-005 平均游戏时长拉长（60s→90s） | 体验 | L | monitoring |

---

## 7. 下一步

设计文档 sign-off 后，进入 **eng-decompose 阶段**：
- 03-decomposition.md（模块树 + WBS）
- 03-tasks/*.md（每个任务独立文件）
- 03-task-graph.md（mermaid 依赖图）

---

## 8. 变更日志

- **2026-06-20 11:43** v1.1 — 沐风补充需求：消除时倒计时奖励
  - 新增 ADR-004（方案 A：+5s/上限 90s）
  - 新增 TimerEngine 模块
  - 更新时序图（3.1 + 3.2）
  - 新增需求 FR-6.5 ~ FR-6.9
  - 新增 R-004 / R-005 风险

- **2026-06-20 11:26** v1.0 — 设计文档 sign-off，4 个 ADR 全部 Accepted