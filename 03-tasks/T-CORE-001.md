# T-CORE-001: BoardEngine 初始填充 + 交换

**状态：** ✅ done
**优先级：** P0
**预估：** 4h
**Owner：** main agent
**依赖：** T-CORE-008
**可并行：** -

## 目标

实现棋盘的初始随机填充（确保无三连）和相邻寿司交换。

## 来源

- 需求：FR-1.2（首屏无三连）, FR-2.3（只能交换相邻）
- 设计：02-design.md §3.2

## 验收标准

- [x] `generateInitialBoard()` 返回 7×7 棋盘，无任何三连
- [x] `attemptSwap(from, to)` 交换相邻寿司，返回 `Result`
- [x] 相邻判定：上下左右 4 方向
- [x] 非相邻返回 `InvalidSwap`
- [x] 交换锁定（swapLock）机制：动画中禁止交换

## 技术要点

- 死循环兜底（7^-49 概率）
- 返回 `Result` 而不是抛异常（Kotlin 风格）
- `Board.copy()` 不可变更新

## 产出物

- 代码：`engine/BoardEngine.kt`
- 测试：`test/engine/BoardEngineTest.kt`（覆盖在 T-CORE-007 中）

## 备注

- 初始填充生成器要单测覆盖 100 次随机种子