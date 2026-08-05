# T-ENH-001: combo 数字弹出提示

**状态：** ⚪ todo
**优先级：** P1
**预估：** 3h
**Owner：** main agent
**依赖：** T-UI-004
**可并行：** T-ENH-002

## 目标

消除时屏幕中央显示 "x2"、"x3" 等 combo 倍数提示。

## 来源

- 需求：FR-4.3（连锁时显示 combo 计数）
- 设计：02-arch-diagram.md §3.1（combo 显示）

## 验收标准

- [ ] `ComboOverlay(comboCount)` Composable
- [ ] 屏幕中央显示 "x2" / "x3" / ...
- [ ] 大字号（64sp）
- [ ] 弹出 + 渐隐动画（400ms）
- [ ] comboCount 0 时不显示

## 技术要点

- `AnimatedVisibility` + `Modifier.scaleIn` 弹出
- 监听 `state.comboCount` 变化

## 产出物

- `ui/screen/ComboOverlay.kt`