# T-CORE-003: GravityEngine 下落填充

**状态：** ✅ done
**优先级：** P0
**预估：** 4h
**Owner：** main agent
**依赖：** T-CORE-001
**可并行：** T-CORE-002

## 目标

消除后实现重力下落，顶部补 null 占位（后续填充新寿司）。

## 来源

- 需求：FR-4.1（消除后上方寿司自动下落填充）
- 设计：02-design.md §3.3

## 验收标准

- [x] `applyGravity(board, eliminatedMatches)` 返回新棋盘
- [x] 被消除的位置变 null
- [x] 每列独立下落：非 null 寿司下落，顶部补 null
- [x] 寿司 row/col 字段同步更新
- [x] 无消除时返回原棋盘（不复制）

## 技术要点

- 列内 `filterNotNull` + `add(0, null)` 填空
- 用 `Board.copy()` 不可变更新
- 复杂度 O(n²)

## 产出物

- 代码：`engine/GravityEngine.kt`
- 测试：`test/engine/GravityEngineTest.kt`（覆盖在 T-CORE-007 中）