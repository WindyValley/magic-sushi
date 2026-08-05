# T-CORE-004: CascadeEngine 连锁递归

**状态：** ✅ done
**优先级：** P0
**预估：** 4h
**Owner：** main agent
**依赖：** T-CORE-002, T-CORE-003
**可并行：** -

## 目标

递归检测连锁：每次消除后下落，再次检测三连，直到没有新匹配。

## 来源

- 需求：FR-4.1/4.2（消除→下落→再次检测）
- 设计：02-design.md §2.2（CascadeEngine 模块职责）

## 验收标准

- [ ] `cascadeUntilStable(board, initialMatches)` 返回所有 Match 列表（按时间顺序）
- [ ] 每次循环：消除 → 下落 → 检测
- [ ] 没有新匹配时停止
- [ ] 极端情况：单次消除引发 5 次连锁
- [ ] 防止死循环（兜底：最多 20 次）

## 技术要点

- 复用 MatchEngine + GravityEngine
- 用 listOfLists 记录每次的 Match
- 兜底上限 20 次连锁

## 产出物

- 代码：`engine/CascadeEngine.kt`
- 测试：`test/engine/CascadeEngineTest.kt`（覆盖在 T-CORE-007 中）