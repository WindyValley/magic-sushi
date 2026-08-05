# T-CORE-006: TimerEngine 倒计时 + 奖励

**状态：** ✅ done
**优先级：** P0
**预估：** 3h
**Owner：** main agent
**依赖：** T-CORE-008
**可并行：** T-CORE-002, T-CORE-003, T-CORE-004, T-CORE-005

## 目标

实现 60s 倒计时 + 消除时 +5s 奖励（上限 90s）。

## 来源

- 需求：FR-6.1 (60s) / FR-6.5 ~ FR-6.9 (奖励机制)
- 设计：ADR-004 / 02-arch-diagram.md §3.2
- ADR：ADR-004-倒计时奖励.md

## 验收标准

- [ ] `TimerEngine` 单例
- [ ] `INITIAL_SECONDS = 60` / `REWARD_SECONDS = 5` / `MAX_SECONDS = 90`
- [ ] `tick(state)` 减 1 秒
- [ ] `rewardOnMatch(state)` 加 5 秒，上限 90
- [ ] `isGameOver(state)` 当 remaining ≤ 0
- [ ] 无效交换（matches 空）不调用 rewardOnMatch

## 技术要点

- 纯函数，不持有状态
- 用 `coerceAtMost(MAX_SECONDS)` 防止溢出
- 单测要覆盖 5 个边界场景（ADR-004 列了）

## 产出物

- 代码：`engine/TimerEngine.kt`
- 测试：`test/engine/TimerEngineTest.kt`（覆盖在 T-CORE-007 中）

## 备注

- 这是 v1.1 新增的（沐风 11:43 补充需求）
- 与 MatchEngine / GravityEngine 完全独立，可最早并行开发