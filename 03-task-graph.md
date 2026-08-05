# Magic Sushi 任务依赖图

> **追溯：** eng-decompose skill 输出
> **输入：** `03-decomposition.md`
> **图规则：** 优先 Mermaid（按 eng-design v2）

---

## 1. 完整依赖图（Mermaid）

```mermaid
graph TD
    %% ===== 核心层（无 Android 依赖）=====
    T-CORE-008["T-CORE-008<br/>数据模型"]:::core
    T-CORE-001["T-CORE-001<br/>BoardEngine"]:::core
    T-CORE-002["T-CORE-002<br/>MatchEngine"]:::core
    T-CORE-003["T-CORE-003<br/>GravityEngine"]:::core
    T-CORE-004["T-CORE-004<br/>CascadeEngine"]:::core
    T-CORE-005["T-CORE-005<br/>ScoreEngine"]:::core
    T-CORE-006["T-CORE-006<br/>TimerEngine"]:::core
    T-CORE-007["T-CORE-007<br/>核心层单测"]:::core

    %% ===== Android 工程 =====
    T-ANDROID-001["T-ANDROID-001<br/>工程脚手架"]:::android
    T-ANDROID-002["T-ANDROID-002<br/>Gradle 配置"]:::android
    T-ANDROID-003["T-ANDROID-003<br/>Manifest + Activity"]:::android

    %% ===== 资源 =====
    T-RES-001["T-RES-001<br/>寿司 PNG"]:::res
    T-RES-002["T-RES-002<br/>音效 OGG"]:::res

    %% ===== UI 层 =====
    T-UI-001["T-UI-001<br/>Theme + Color"]:::ui
    T-UI-002["T-UI-002<br/>GameCanvas"]:::ui
    T-UI-003["T-UI-003<br/>SushiTile 触控"]:::ui
    T-UI-004["T-UI-004<br/>ScoreOverlay"]:::ui
    T-UI-005["T-UI-005<br/>TimerDisplay + +5s"]:::ui
    T-UI-006["T-UI-006<br/>PauseDialog"]:::ui
    T-UI-007["T-UI-007<br/>GameOverDialog"]:::ui

    %% ===== ViewModel + 数据 + 音效 =====
    T-VM-001["T-VM-001<br/>GameViewModel"]:::vm
    T-VM-002["T-VM-002<br/>集成 Timer"]:::vm
    T-DATA-001["T-DATA-001<br/>PrefsRepo"]:::data
    T-AUDIO-001["T-AUDIO-001<br/>SoundPlayer"]:::audio

    %% ===== 集成 + 测试 + 文档 =====
    T-BUILD-001["T-BUILD-001<br/>debug APK 编译"]:::build
    T-TEST-001["T-TEST-001<br/>手测 60s 一局"]:::test
    T-DOC-001["T-DOC-001<br/>README + release notes"]:::doc

    %% ===== 增强 =====
    T-ENH-001["T-ENH-001<br/>combo 提示"]:::enh
    T-ENH-002["T-ENH-002<br/>最后 10s 红闪"]:::enh
    T-ENH-003["T-ENH-003<br/>About + LICENSE"]:::enh

    %% ===== 依赖关系（核心层）=====
    T-CORE-008 --> T-CORE-001
    T-CORE-008 --> T-CORE-006
    T-CORE-001 --> T-CORE-002
    T-CORE-001 --> T-CORE-003
    T-CORE-002 --> T-CORE-004
    T-CORE-003 --> T-CORE-004
    T-CORE-002 --> T-CORE-005
    T-CORE-004 --> T-CORE-007
    T-CORE-005 --> T-CORE-007
    T-CORE-006 --> T-CORE-007

    %% ===== Android 工程 =====
    T-ANDROID-001 --> T-ANDROID-002
    T-ANDROID-002 --> T-ANDROID-003
    T-ANDROID-002 --> T-UI-001
    T-ANDROID-002 --> T-DATA-001
    T-ANDROID-002 --> T-AUDIO-001
    T-ANDROID-002 --> T-RES-001
    T-ANDROID-002 --> T-RES-002

    %% ===== UI 层 =====
    T-UI-001 --> T-UI-002
    T-UI-001 --> T-UI-004
    T-UI-001 --> T-UI-005
    T-UI-001 --> T-UI-006
    T-UI-001 --> T-UI-007
    T-CORE-001 --> T-UI-002
    T-RES-001 --> T-UI-002
    T-UI-002 --> T-UI-003
    T-CORE-006 --> T-UI-005

    %% ===== ViewModel =====
    T-CORE-007 --> T-VM-001
    T-DATA-001 --> T-VM-001
    T-AUDIO-001 --> T-VM-001
    T-VM-001 --> T-VM-002
    T-CORE-006 --> T-VM-002

    %% ===== 集成 =====
    T-VM-002 --> T-BUILD-001
    T-UI-003 --> T-BUILD-001
    T-UI-004 --> T-BUILD-001
    T-UI-005 --> T-BUILD-001
    T-UI-006 --> T-BUILD-001
    T-UI-007 --> T-BUILD-001
    T-BUILD-001 --> T-TEST-001
    T-TEST-001 --> T-DOC-001

    %% ===== 增强 =====
    T-UI-004 --> T-ENH-001
    T-UI-005 --> T-ENH-002
    T-DOC-001 --> T-ENH-003

    %% ===== 关键路径高亮（红色）=====
    classDef criticalPath stroke:#ff0000,stroke-width:3px,color:#000;
    class T-CORE-008,T-CORE-001,T-CORE-002,T-CORE-004,T-CORE-007,T-ANDROID-001,T-ANDROID-002,T-UI-002,T-UI-003,T-VM-001,T-VM-002,T-BUILD-001 criticalPath;

    %% ===== 颜色分类 =====
    classDef core fill:#e1f5ff,stroke:#0277bd;
    classDef android fill:#fff3e0,stroke:#e65100;
    classDef res fill:#f3e5f5,stroke:#6a1b9a;
    classDef ui fill:#e8f5e9,stroke:#2e7d32;
    classDef vm fill:#fce4ec,stroke:#c2185b;
    classDef data fill:#e0f7fa,stroke:#00838f;
    classDef audio fill:#fff8e1,stroke:#ff8f00;
    classDef build fill:#ffebee,stroke:#c62828;
    classDef test fill:#f1f8e9,stroke:#558b2f;
    classDef doc fill:#ede7f6,stroke:#4527a0;
    classDef enh fill:#fbe9e7,stroke:#bf360c;
```

## 2. 关键路径（最长串行链）

```
T-CORE-008 (Models) ──→ T-CORE-001 (Board) ──→ T-CORE-002 (Match) ──→
T-CORE-004 (Cascade) ──→ T-CORE-007 (Tests) ──→
T-ANDROID-001 ──→ T-ANDROID-002 ──→
T-UI-002 (Canvas) ──→ T-UI-003 (触控) ──→
T-VM-001 (ViewModel) ──→ T-VM-002 (Timer) ──→
T-BUILD-001 (APK)

总链长：13 个任务 / 约 50h
```

## 3. 并行分组（实际串行执行，但识别为独立）

| 组 | 任务 | 关系 |
|----|------|------|
| **A** | T-CORE-002, T-CORE-003 | 都需要 T-CORE-001，互不依赖 |
| **B** | T-CORE-006 (Timer) | 只需 T-CORE-008，与核心层其它独立 |
| **C** | T-RES-001, T-RES-002 | 资源下载，互相独立 |
| **D** | T-UI-004, T-UI-005, T-UI-006, T-UI-007 | 都需要 T-UI-001，互相独立 |
| **E** | T-ANDROID-002 → 4 个下游 | T-ANDROID-002 完成后 T-UI-001/T-DATA-001/T-AUDIO-001 可并行 |

## 4. 图例

| 颜色 | 类别 |
|------|------|
| 🔵 蓝 | core（核心逻辑层） |
| 🟠 橙 | android（Android 工程） |
| 🟣 紫 | res（资源） |
| 🟢 绿 | ui（UI 层） |
| 🌸 粉 | vm（ViewModel） |
| 🩵 青 | data（数据） |
| 🟡 黄 | audio（音效） |
| 🔴 红 | build（构建） |
| 🟢 浅绿 | test（测试） |
| 🟪 深紫 | doc（文档） |
| 🟥 红边 | 关键路径（红色描边） |

## 5. 节点状态

- ⚪ todo（待开始）
- 🟡 in_progress（进行中）
- ✅ done（完成）
- 🔴 blocked（阻塞）

> 状态由 eng-dev 阶段维护，每个 T-*.md 文件的 frontmatter 标注。
> 本图渲染时按状态着色（todo=灰 / in_progress=黄 / done=绿 / blocked=红）。

## 6. 下一步

本图用于 eng-dev 阶段：
1. 按关键路径顺序开发
2. 完成一个 T-*.md 状态 → ⚪ → 🟡 → ✅
3. 阻塞立即登记到 `PROJECT_STATUS.md` Blockers 区块
4. eng-dev 完成后本图全 ✅ → 进入 eng-test-plan