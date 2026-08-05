# T-ENH-002: 最后 10s 倒计时数字变红 + 闪烁

**状态：** ⚪ todo
**优先级：** P2
**预估：** 2h
**Owner：** main agent
**依赖：** T-UI-005
**可并行：** T-ENH-001

## 目标

倒计时 ≤ 10s 时数字变红 + 闪烁。

## 来源

- 需求：FR-6.3（最后 10s 数字变红 + 闪烁）

## 验收标准

- [ ] TimerDisplay 监听 remainingSeconds
- [ ] 当 remaining ≤ 10 时 color = Color.Red
- [ ] 闪烁：alpha 1.0 ↔ 0.3，1 秒周期
- [ ] remaining > 10 时恢复正常

## 技术要点

- `animateColorAsState(Color.Red ↔ Color.Black)`
- `LaunchedEffect(remainingSeconds) { while (true) { delay(500); toggle } }`

## 产出物

- 修改 `ui/screen/TimerDisplay.kt`