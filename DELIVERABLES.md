# Magic Sushi 交付物清单

> 按 eng-workflow 的 7 阶段组织。每个阶段产物是 gate 的判定依据。

## 阶段 0：项目启动 ✅
- [x] `projects/magic-sushi/` 目录创建
- [x] README.md（项目入口）
- [x] DELIVERABLES.md（本文件）
- [x] references/ 目录

## 阶段 1：需求分析（requirements） ✅
- [x] `01-requirements.md` **v1.0 sign-off**
  - [x] 项目概述
  - [x] 用户故事（US-001 ~ US-007，含拖动增强 US-003）
  - [x] 功能需求（FR-1 ~ FR-7，共 27 条）
  - [x] 非功能需求（NFR-1 ~ NFR-5）
  - [x] 验收标准（AC-1 ~ AC-4）
  - [x] 已确认决策（Q1 ~ Q8 全部答复）

## 阶段 2：架构设计（design） ✅ v1.1
- [x] `02-design.md` v1.1 sign-off ✅（含 ADR-004 变更）
- [x] `02-tech-stack.md` ✅
- [x] `02-arch-diagram.md` ✅（时序图已更新含 TimerEngine）
- [x] `02-adr/ADR-001-触控交互实现.md` ✅ Accepted
- [x] `02-adr/ADR-002-状态管理.md` ✅ Accepted
- [x] `02-adr/ADR-003-动画引擎.md` ✅ Accepted
- [x] `02-adr/ADR-004-倒计时奖励.md` ✅ Accepted ⭐ NEW
- [x] `02-risk.md` ✅（含 R-005/R-006）

## 阶段 3：任务拆分（decompose） ✅ v1.0
- [x] `03-decomposition.md` v1.0 sign-off ✅
  - 模块树 + WBS
  - 任务清单（27 个，22 P0 / 4 P1 / 1 P2）
  - 总估时 93h
  - 关键路径 50h（13 任务）
- [x] `03-task-graph.md` ✅（Mermaid 依赖图，关键路径标红）
- [x] `03-tasks/T-CORE-008.md` ✅ Models 数据模型
- [x] `03-tasks/T-CORE-001.md` ✅ BoardEngine
- [x] `03-tasks/T-CORE-002.md` ✅ MatchEngine
- [x] `03-tasks/T-CORE-003.md` ✅ GravityEngine
- [x] `03-tasks/T-CORE-004.md` ✅ CascadeEngine
- [x] `03-tasks/T-CORE-005.md` ✅ ScoreEngine
- [x] `03-tasks/T-CORE-006.md` ✅ TimerEngine
- [x] `03-tasks/T-CORE-007.md` ✅ 核心层单测
- [x] `03-tasks/T-ANDROID-001.md` ✅ 工程脚手架
- [x] `03-tasks/T-ANDROID-002.md` ✅ Gradle 配置
- [x] `03-tasks/T-ANDROID-003.md` ✅ Manifest + Activity
- [x] `03-tasks/T-RES-001.md` ✅ 寿司 PNG
- [x] `03-tasks/T-RES-002.md` ✅ 音效 OGG
- [x] `03-tasks/T-UI-001.md` ✅ Theme + Color
- [x] `03-tasks/T-UI-002.md` ✅ GameCanvas
- [x] `03-tasks/T-UI-003.md` ✅ SushiTile 触控
- [x] `03-tasks/T-UI-004.md` ✅ ScoreOverlay
- [x] `03-tasks/T-UI-005.md` ✅ TimerDisplay + +5s
- [x] `03-tasks/T-UI-006.md` ✅ PauseDialog
- [x] `03-tasks/T-UI-007.md` ✅ GameOverDialog
- [x] `03-tasks/T-VM-001.md` ✅ GameViewModel
- [x] `03-tasks/T-VM-002.md` ✅ 集成 Timer reward
- [x] `03-tasks/T-DATA-001.md` ✅ PrefsRepository
- [x] `03-tasks/T-AUDIO-001.md` ✅ SoundPlayer
- [x] `03-tasks/T-BUILD-001.md` ✅ debug APK 编译
- [x] `03-tasks/T-TEST-001.md` ✅ 手测 60s 一局
- [x] `03-tasks/T-DOC-001.md` ✅ README + release notes
- [x] `03-tasks/T-ENH-001.md` ✅ combo 提示
- [x] `03-tasks/T-ENH-002.md` ✅ 最后 10s 红闪
- [x] `03-tasks/T-ENH-003.md` ✅ About + LICENSE

## 阶段 4：开发（dev） ⚪
- [ ] `android-app/` Android Studio 工程
- [ ] 核心库代码（按 design 决策：C/C++ 或 Kotlin）
- [ ] UI 代码（Compose）
- [ ] 资源文件（寿司图片、音效，源自 EXL）
- [ ] 编译产出：`app-debug.apk`

## 阶段 5：测试文档（test-plan） ⚪
- [ ] `04-test-plan.md`（测试策略）
- [ ] `04-cases.md`（测试用例）

## 阶段 6：自动化测试（automate-test） ⚪
- [ ] 测试脚本（Espresso UI 测试）
- [ ] 单元测试脚本（核心库）
- [ ] CI 配置（GitHub Actions）

## 阶段 7：发布（done） ⚪
- [ ] `05-release-notes.md`（发布说明）

---

## 当前进度

**已完成：** 3/7 阶段（requirements v1.1 + design v1.1 + decompose v1.0）
**进行中：** 阶段 4（dev）→ 等沐风 review decompose 后启动
**阻塞：** 无