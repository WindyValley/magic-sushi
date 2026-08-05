# T-CORE-002: MatchEngine 三连检测

**状态：** ✅ done
**优先级：** P0
**预估：** 6h
**Owner：** main agent (subagent-I 实施)
**依赖：** T-CORE-001
**可并行：** T-CORE-003
**完成时间：** 2026-06-20 15:00
**产出：** `engine/MatchEngine.kt`（~14 KB，含 12 项 fun main 自测全通过）

## 目标

检测棋盘上所有横/竖三连（4 连、5 连也支持），返回 `List<Match>`。

## 来源

- 需求：FR-3.1（横/竖三连）, FR-3.2（4/5 连额外加分）, FR-3.3（不支持斜线）, FR-3.4（首屏初始化）
- 设计：02-design.md §3.1

## ⚠️ 关键依赖字段

使用 `Match.axis: MatchAxis`（**不是** `Match.direction: Direction`）。
- `MatchAxis.HORIZONTAL` = 横行
- `MatchAxis.VERTICAL` = 竖列
- `Direction` 是 cardinal（4 值），仅用于手势/重力，**不要** 在 Match 中使用

（2026-06-20 13:47 决策，从 v1 改名为 v2）

## 验收标准

- [ ] `detectMatches(board)` 返回所有 Match 列表
- [ ] 每个 Match 使用 `axis: MatchAxis` 标注横/竖
- [ ] 支持横/竖方向
- [ ] 不支持斜线
- [ ] 4 连/5 连能正确识别（length 字段）
- [ ] 同一寿司在多个 Match 中会去重
- [ ] 边界情况：满三连 + 拐角（L 形）

## 技术要点

- 滑动窗口算法，O(n²)
- 横竖各扫描一遍，传 `MatchAxis.HORIZONTAL` 或 `MatchAxis.VERTICAL`
- 复用 BoardEngine 初始填充验证

## 产出物

- 代码：`engine/MatchEngine.kt`
- 测试：`test/engine/MatchEngineTest.kt`（覆盖在 T-CORE-007 中）

## 参考实现

`02-design.md` §3.1 已更新为 v2 版本（使用 `MatchAxis`）：
```kotlin
fun detectMatches(board: Board): List<Match> {
    // 横向
    for (row in 0 until board.size) {
        detectLineMatches(board.grid[row], MatchAxis.HORIZONTAL)?.let { ... }
    }
    // 竖向
    for (col in 0 until board.size) {
        val column = board.grid.map { it[col] }.toTypedArray()
        detectLineMatches(column, MatchAxis.VERTICAL)?.let { ... }
    }
}
```