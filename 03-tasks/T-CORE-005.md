# T-CORE-005: ScoreEngine 计分

**状态：** ✅ done
**优先级：** P0
**预估：** 3h
**Owner：** main agent
**依赖：** T-CORE-002
**可并行：** -

## 目标

根据消除的寿司数和 combo 数计算得分。

## 来源

- 需求：FR-5.1（基础 +10/寿司）, FR-5.2（combo 加成）, FR-5.3（4 连+20/5 连+50）
- 设计：02-design.md §2.2

## 验收标准

- [ ] `calculateScore(matches, comboCount)` 返回 `ScoreResult(totalScore, breakdown)`
- [ ] 基础分：`sum(tiles.size) * 10`
- [ ] combo 加成：`(comboCount - 1)` 倍
- [ ] 4 连额外 +20，5 连额外 +50
- [ ] 边界：combo=1 不加成

## 技术要点

- 纯函数，输入 matches + combo，输出 ScoreResult
- 返回 breakdown 用于 UI 显示（动画 / 飘字）

## 产出物

- 代码：`engine/ScoreEngine.kt`
- 测试：`test/engine/ScoreEngineTest.kt`（覆盖在 T-CORE-007 中）