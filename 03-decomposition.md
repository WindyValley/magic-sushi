# Magic Sushi 任务拆分总览

> **状态：** decompose v1.0
> **最后更新：** 2026-06-20 12:15
> **追溯：** eng-decompose skill 输出
> **输入：** `02-design.md` + `01-requirements.md`
> **team_size：** 1（单人 MVP，全部 owner=main）
> **sprint_length：** 14 天

---

## 1. 模块树

```
projects/magic-sushi/
├── core/                          # 核心逻辑层（无 Android 依赖）
│   ├── models/                    # 数据模型
│   └── engine/                    # 业务引擎
├── ui/                            # Compose UI 层
│   ├── canvas/                    # Canvas 绘制
│   ├── screen/                    # 屏幕组件
│   └── theme/                     # 主题
├── viewmodel/                     # ViewModel
├── data/                          # 数据持久化
├── audio/                         # 音效封装
└── android-app/                   # Android 工程脚手架
    ├── gradle/                    # 构建脚本
    ├── res/                       # 资源（drawable + raw）
    └── manifest/                  # AndroidManifest
```

## 2. WBS（按交付物反向推导）

```
Magic Sushi v1.0
├── 可运行的 Android App
│   ├── debug APK（能安装到 Android 8.0+）
│   ├── 完整可玩一局 60s（计时 + 计分 + 消除 + 连锁）
│   └── 触控交互（点击 + 拖动 + 交换）
├── 核心游戏逻辑（纯 Kotlin，可单测）
│   ├── BoardEngine / MatchEngine / CascadeEngine
│   ├── GravityEngine / ScoreEngine / TimerEngine
│   └── Models（Board/SushiTile/Match/Direction/SushiType）
├── Android UI（Compose + Canvas）
│   ├── GameScreen / GameCanvas
│   ├── ScoreOverlay / TimerDisplay
│   └── PauseDialog / GameOverDialog
├── 数据层
│   └── PrefsRepository（最高分本地保存）
├── 资源
│   ├── 6 种寿司 PNG（来自 EXL）
│   └── 4 种音效 OGG（来自 EXL）
└── 文档
    ├── README（完善）
    └── 05-release-notes.md
```

## 3. 任务清单（27 个任务）

> **优先级：** P0 = 关键路径必做 / P1 = 体验增强 / P2 = 可选优化
> **Owner：** main（单人 MVP）
> **估算粒度：** 1-3 天（实际小时数）

### 3.1 核心层任务（8 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-CORE-008** | 数据模型定义（Models.kt） | 2h | P0 | - | ⚪ |
| **T-CORE-001** | BoardEngine 初始填充 + 交换 | 4h | P0 | T-CORE-008 | ⚪ |
| **T-CORE-002** | MatchEngine 三连检测 | 6h | P0 | T-CORE-001 | ⚪ |
| **T-CORE-003** | GravityEngine 下落填充 | 4h | P0 | T-CORE-001 | ⚪ |
| **T-CORE-004** | CascadeEngine 连锁递归 | 4h | P0 | T-CORE-002, T-CORE-003 | ⚪ |
| **T-CORE-005** | ScoreEngine 计分 | 3h | P0 | T-CORE-002 | ⚪ |
| **T-CORE-006** | TimerEngine 倒计时 + 奖励 | 3h | P0 | T-CORE-008 | ⚪ |
| **T-CORE-007** | 核心层单元测试（>80% 覆盖） | 8h | P0 | T-CORE-004, T-CORE-005, T-CORE-006 | ⚪ |

### 3.2 Android 工程脚手架（3 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-ANDROID-001** | Android Studio 工程脚手架 | 2h | P0 | - | ⚪ |
| **T-ANDROID-002** | Gradle 配置（Kotlin/Compose/SDK） | 3h | P0 | T-ANDROID-001 | ⚪ |
| **T-ANDROID-003** | AndroidManifest + 包名 + Activity | 2h | P0 | T-ANDROID-002 | ⚪ |

### 3.3 资源（2 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-RES-001** | 借用 EXL 寿司 PNG 素材（6 种） | 2h | P0 | - | ⚪ |
| **T-RES-002** | 借用 EXL 音效 OGG（4 种） | 2h | P0 | - | ⚪ |

### 3.4 UI 层任务（7 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-UI-001** | Compose Theme + Color | 2h | P0 | T-ANDROID-002 | ⚪ |
| **T-UI-002** | GameCanvas + SushiPainter（Canvas 绘制） | 8h | P0 | T-CORE-001, T-RES-001, T-UI-001 | ⚪ |
| **T-UI-003** | SushiTile 触控交互（ADR-001） | 6h | P0 | T-UI-002 | ⚪ |
| **T-UI-004** | ScoreOverlay 组件 | 3h | P0 | T-UI-001 | ⚪ |
| **T-UI-005** | TimerDisplay + +5s 飘字 | 3h | P0 | T-CORE-006, T-UI-001 | ⚪ |
| **T-UI-006** | PauseDialog | 2h | P1 | T-UI-001 | ⚪ |
| **T-UI-007** | GameOverDialog 结算弹窗 | 3h | P0 | T-UI-001 | ⚪ |

### 3.5 ViewModel + 数据 + 音效（4 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-VM-001** | GameViewModel + Factory | 6h | P0 | T-CORE-* 全集 | ⚪ |
| **T-VM-002** | 集成 TimerEngine 奖励到 ViewModel | 2h | P0 | T-VM-001, T-CORE-006 | ⚪ |
| **T-DATA-001** | PrefsRepository 最高分读写 | 3h | P0 | T-ANDROID-002 | ⚪ |
| **T-AUDIO-001** | SoundPlayer 音效封装 | 3h | P1 | T-RES-002, T-ANDROID-002 | ⚪ |

### 3.6 集成 + 测试 + 文档（3 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-BUILD-001** | debug APK 编译 + 安装到模拟器 | 1h | P0 | T-VM-002, T-UI-* 全集 | ⚪ |
| **T-TEST-001** | 手动测试完整 60s 一局 + 验证 FR | 4h | P0 | T-BUILD-001 | ⚪ |
| **T-DOC-001** | README 完善 + 05-release-notes.md | 3h | P1 | T-TEST-001 | ⚪ |

### 3.7 增强（P1/P2，3 个）

| ID | 标题 | 估时 | 优先级 | 依赖 | 状态 |
|----|------|------|--------|------|------|
| **T-ENH-001** | combo 数字弹出提示（x2/x3...） | 3h | P1 | T-UI-004 | ⚪ |
| **T-ENH-002** | 最后 10s 倒计时数字变红 + 闪烁 | 2h | P2 | T-UI-005 | ⚪ |
| **T-ENH-003** | About 页面致谢 EXL + LICENSE 审查 | 2h | P1 | T-DOC-001 | ⚪ |

## 4. 统计

| 维度 | 值 |
|------|-----|
| 任务总数 | 27 |
| P0 任务 | 22 |
| P1 任务 | 4 |
| P2 任务 | 1 |
| 总估时 | ~93h（约 12 个工作日） |
| 关键路径任务 | 11 个（T-CORE-* + T-ANDROID-002 + T-UI-002/003 + T-VM-001 + T-BUILD-001） |
| 并行机会 | T-CORE-006 (Timer) 可与 T-CORE-004 (Cascade) 并行 |

## 5. Owner 分配策略

按 eng-decompose 的策略：

1. **绑定到人/agent**：沐风指定 → main agent（实际只有我）
2. **按技能自动推荐**：
   - C/C++ → 嵌入式 / 后端 agent（无 C/C++ 任务，纯 Kotlin）
   - 各端 UI → 移动 agent（→ main）
   - Go/Rust 服务端 → 后端 agent（无）
3. **均匀分配**：单 agent 全包
4. **学习机会**：单人 MVP，无 mentor

**结论**：所有任务 owner = `main` agent。

## 6. Sprint 划分（单人 MVP，简化）

虽然 eng-decompose 默认 14 天 sprint，但单 agent MVP 简单分为 2 个 phase：

### Phase 1：核心层 + Android 骨架（第 1-3 天）

```
T-CORE-008 → T-CORE-001/003/006 → T-CORE-002/004/005 → T-CORE-007
+ T-ANDROID-001/002/003
+ T-RES-001/002
```

### Phase 2：UI + ViewModel + 集成（第 4-7 天）

```
T-UI-001 → T-UI-002 → T-UI-003 → T-UI-004/005/006/007
+ T-VM-001 → T-VM-002
+ T-DATA-001 → T-AUDIO-001
+ T-BUILD-001 → T-TEST-001 → T-DOC-001
+ T-ENH-*（按优先级）
```

## 7. 关键路径（必须串行）

```
T-CORE-008 (Models)
    ↓
T-CORE-001 (Board)
    ↓
T-CORE-002 (Match)
    ↓
T-CORE-004 (Cascade)
    ↓
T-CORE-007 (Tests)
    ↓
T-ANDROID-001 → T-ANDROID-002 → T-ANDROID-003
    ↓
T-UI-002 (Canvas)
    ↓
T-UI-003 (触控)
    ↓
T-VM-001 (ViewModel)
    ↓
T-VM-002 (集成 Timer)
    ↓
T-BUILD-001 (APK)
    ↓
T-TEST-001 (手测)
```

**关键路径总估时：约 50h（链长）**

## 8. 并行机会

可以并行执行的任务组（因为 owner 都是 main 串行执行，但识别为"逻辑独立"）：

| 组 | 任务 | 并行理由 |
|----|------|---------|
| A | T-CORE-002, T-CORE-003 | 都需要 T-CORE-001，互不依赖 |
| B | T-CORE-006 (Timer) | 只需 T-CORE-008，与 T-CORE-002/003/004/005 完全独立 |
| C | T-RES-001, T-RES-002 | 资源下载，互相独立 |
| D | T-UI-004, T-UI-005, T-UI-006, T-UI-007 | 都需要 T-UI-001，互相独立 |

## 9. 输出文件

- `03-decomposition.md`（本文件，总览）
- `03-task-graph.md`（mermaid 依赖图）
- `03-tasks/T-*.md`（27 个任务文件，每个独立）

## 10. 下一步

decompose sign-off 后进入 **eng-dev**：
- 按 T-CORE-* → T-ANDROID-* → T-UI-* → T-VM-* → T-BUILD-* 顺序开发
- 每个任务完成后更新 `03-tasks/T-*.md` 状态为 `done`
- 关键路径完成后立即编译一次（T-BUILD-001）
- 最终交付物：`android-app/app-debug.apk`

## 11. 变更日志

- **2026-06-20 12:15** v1.0 — decompose sign-off，27 个任务，93h 估时，单 agent 全包