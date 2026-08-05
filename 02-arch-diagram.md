# Magic Sushi 架构图

> C4 Model 三层视图。对应 02-design.md §3

---

## Level 1: Context（系统上下文）

```mermaid
graph LR
    User[玩家]
    App[魔法寿司 App]
    System[Android 系统]
    User --> App
    App --> System
```

**说明：**
- 玩家打开 App → 直接进入游戏棋盘
- App 完全运行在 Android 系统上，**无需网络**
- 无服务端、无第三方服务

---

## Level 2: Container（容器视图）

```mermaid
graph TB
    subgraph App[魔法寿司 App - top.windyvalley.magicsushi]
        subgraph UI[UI 层 - Compose]
            GameScreen["🎮 GameScreen\n(Composable)"]
            GameCanvas["🎯 GameCanvas\n(CanvasComposable)"]
            SushiTile["🖱️ SushiTile\n(触控手势)"]
            ScoreOverlay["📊 ScoreOverlay\n(Composable)"]
            TimerDisplay["⏱️ TimerDisplay\n(Composable)"]
            RewardOverlay["✨ RewardOverlay\n(+5s飘字)"]
            PauseDialog["⏸️ PauseDialog\n(Composable)"]
            GameOverDialog["🏁 GameOverDialog\n(Composable)"]
        end

        subgraph State["状态层 - ViewModel"]
            GameState["🧠 GameState\n(data class + StateFlow)"]
        end

        subgraph Logic["核心逻辑层 - Kotlin\n(无 UI 依赖)"]
            BoardEngine["🔲 BoardEngine\n(棋盘状态)"]
            MatchEngine["🔍 MatchEngine\n(消除判定)"]
            CascadeEngine["⚡ CascadeEngine\n(连锁检测)"]
            ScoreEngine["🏆 ScoreEngine\n(计分)"]
            GravityEngine["⬇️ GravityEngine\n(下落)"]
            TimerEngine["⏰ TimerEngine\n(倒计时/奖励)"]
            AnimationEngine["🎬 AnimationEngine\n(动画帧生成)"]
        end

        subgraph Storage["持久化层"]
            PrefsRepo["💾 PrefsRepository\n(SharedPreferences)"]
            SoundPlayer["🔊 SoundPlayer\n(SoundPool)"]
        end
    end
```

**说明：**
- **UI 层**：100% Compose，不含任何 Android View
- **状态层**：Jetpack ViewModel + StateFlow
- **核心逻辑层**：纯 Kotlin 业务逻辑，**无任何 Compose/Android 依赖**，可独立单元测试
- **持久化层**：SharedPreferences，仅存最高分

---

## Level 3: Component - 核心交互时序

### 3.1 交换 → 消除 → 连锁完整流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant G as GameCanvas
    participant GS as GameViewModel
    participant BE as BoardEngine
    participant ME as MatchEngine
    participant TE as TimerEngine
    participant CE as CascadeEngine
    participant SE as ScoreEngine
    participant GE as GravityEngine

    U->>G: 拖动寿司 A → 寿司 B 位置
    G->>GS: onDragEnd(A, B)
    GS->>BE: attemptSwap(A, B)
    BE->>ME: detectMatches()
    ME-->>BE: matchesFound: List<Match>
    BE-->>GS: swapResult: Result
    alt matches.isNotEmpty() (v1.0.3)
        GS->>TE: rewardOnMatch()
        TE-->>GS: (60, actualReward)
    end
    GS->>SE: calculateScore(matches, comboCount)
    GS->>GE: applyGravity()
    GE->>CE: detectCascade()
    CE-->>GE: cascadeMatches
    loop Cascade (每步触发)
        GS->>TE: rewardOnMatch()
        GS->>SE: calculateScore(cascade, combo+1)
        GS->>GE: applyGravity
    end
    GS-->>G: emit(newState)
    G->>G: 动画：消除 → 下落 → 填充
```

### 3.2 倒计时流程（含 v1.0.3 奖励机制）

```mermaid
sequenceDiagram
    participant T as TimerEngine
    participant GS as GameViewModel
    participant G as GameScreen

    Note over T: 初始：INITIAL_SECONDS = 60
    GS->>T: initialState()
    T-->>GS: 60

    loop Every second (1Hz tick)
        GS->>T: tick(remaining)
        T-->>GS: remaining-1 (floor at 0)
        GS-->>G: emit(tick)
        G->>G: 更新倒计时显示
    end

    Note over GS,T: 成功消除时（v1.0.3 — 重置到 60s）
    GS->>T: rewardOnMatch(remaining, matches)
    T->>T: newRemaining = 60, reward = max(0, 60-remaining)
    T-->>GS: (newRemaining, actualReward)
    GS-->>G: emit(tick)
    G->>G: 显示 +Xs 飘字（actualReward > 0 时）

    Note over T: 时间归零
    GS->>T: isGameOver(remaining)
    T-->>GS: true
    GS->>GS: GameOver phase
    GS-->>G: emit(GameOver)
    G->>G: 显示结算弹窗
```

---

## Level 4: 棋盘数据结构

```mermaid
classDiagram
    class SushiTile {
        <<data class>>
        id: Int
        type: SushiType
        row: Int
        col: Int
        isSelected: Boolean
        isLocked: Boolean
    }

    class Direction {
        <<enum>>
        UP
        DOWN
        LEFT
        RIGHT
    }

    class MatchAxis {
        <<enum>>
        HORIZONTAL
        VERTICAL
    }

    class Board {
        <<data class>>
        size: Int = 7
        grid: Array<Array~SushiTile?>>
        swapLock: Boolean
        cascadeLock: Boolean
    }

    class Match {
        <<data class>>
        tiles: List~SushiTile~
        axis: MatchAxis
        length: Int
    }

    class GamePhase {
        <<enum>>
        IDLE
        PLAYING
        PAUSED
        GAME_OVER
    }

    class GameState {
        <<data class>>
        board: Board
        score: Int
        comboCount: Int
        remainingSeconds: Int
        phase: GamePhase
        highScore: Int
        isMuted: Boolean
    }

    class TileAnim {
        <<sealed class>>
        FadingOut
        Falling(fromRow, toRow)
        SpawningIn(spawnFromRow)
        Stable
    }

    class CellKey {
        row: Int
        col: Int
    }

    class TileRenderState {
        visualId: Int
        type: SushiType
        alpha: Float
        offsetY: Float
        scale: Float
        anim: TileAnim
    }

    Board "1" *-- "49" SushiTile
    Match --> "2+" SushiTile
    Match --> "1" MatchAxis
    GameState --> "1" GamePhase
```

**Direction vs MatchAxis 说明（2026-06-20 13:47 决策）：**
- `Direction`（cardinal，4 值）：用于交换手势、重力下落
- `MatchAxis`（axial，2 值）：用于 Match 检测出的轴线方向
- 两者独立，不能合并（从 v1 → v2 的冲突修复）

---

## 棋盘 7×7 网格坐标示意

```
     col: 0   1   2   3   4   5   6
        ┌───┬───┬───┬───┬───┬───┬───┐
  row:0 │ 🍣│ 🍙│ 🍣│ 🍣│ 🍙│ 🍣│ 🍙│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:1 │ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:2 │ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:3 │ 🍙│ 🍣│ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:4 │ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│ 🍣│ 🍙│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:5 │ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│
        ├───┼───┼───┼───┼───┼───┼───┤
  row:6 │ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│ 🍙│ 🍣│
        └───┴───┴───┴───┴───┴───┴───┘

  交换相邻: (row,col) ↔ (row±1,col) 或 (row,col±1)
  消除判定: 横→ row 相同, col 连续3+ | 竖→ col 相同, row 连续3+
```

---

## 核心模块职责

| 模块 | 职责 | 对应类 |
|------|------|-------|
| 棋盘状态 | 管理 7×7 网格、寿司放置、交换 | `BoardEngine` |
| 消除判定 | 检测横/竖三连、L/T 形 | `MatchEngine` |
| 连锁 | 递归检测下落后的新三连 | `CascadeEngine` |
| 下落 | 重力填充空位 | `GravityEngine` |
| 计分 | 基础分 + combo 加成 + 连击加成 | `ScoreEngine` |
| 渲染 | Canvas 绘制棋盘 + 动画 | `GameCanvas` (CanvasComposable) |
| 状态VM | UI 状态 + 协调核心层 | `GameViewModel` |

**核心层（无 UI 依赖）**：`BoardEngine` / `MatchEngine` / `CascadeEngine` / `ScoreEngine` / `GravityEngine`
**UI 层**：Compose + Canvas