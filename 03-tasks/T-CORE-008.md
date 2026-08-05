# T-CORE-008: 数据模型定义（Models.kt）

**状态：** ✅ done
**优先级：** P0
**预估：** 2h
**Owner：** main agent
**依赖：** -
**可并行：** -

## 目标

定义所有核心层数据结构（Board、SushiTile、Match、Direction、SushiType、GamePhase）。

## 来源

- 需求：FR-1.1（棋盘 7×7）, FR-1.3（6 种寿司）
- 设计：02-design.md §3 / 02-arch-diagram.md Level 4

## 验收标准

- [ ] `Board`（data class，size + grid + locks）
- [ ] `SushiTile`（data class，id + type + row + col + isSelected + isLocked）
- [ ] `SushiType`（enum，6 个值：Sushi1~Sushi6）
- [ ] `Direction`（enum：UP/DOWN/LEFT/RIGHT）
- [ ] `Match`（data class，tiles + direction + length）
- [ ] `GamePhase`（sealed class：IDLE/ANIMATING/GAME_OVER）
- [ ] 所有模型都是 `data class`，无行为逻辑

## 技术要点

- 纯 Kotlin，无 Android 依赖
- 用 `data class` 而非 class
- enum 用 `entries` 替代 `values()`

## 产出物

- 代码：`android-app/app/src/main/java/top/windyvalley/magicsushi/engine/Models.kt`
- 测试：覆盖在 T-CORE-007 中

## 备注

- 是所有 Engine 的基础，必须先完成
- 字段命名严格按 02-arch-diagram.md Level 4 类图