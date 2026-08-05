# T-ENH-003: About 页面致谢 EXL + LICENSE 审查

**状态：** ⚪ todo
**优先级：** P1
**预估：** 2h
**Owner：** main agent
**依赖：** T-DOC-001
**可并行：** -

## 目标

App 内 About 页面致谢 EXL/Magic-Sushi 项目，审查所有素材 LICENSE。

## 来源

- 设计：references/EXL-Magic-Sushi.md（致谢要求）
- 需求：FR-1.4（素材来源透明）

## 验收标准

- [ ] `AboutScreen` Composable
- [ ] 显示项目说明、版本、作者（沐风）
- [ ] 致谢 EXL/Magic-Sushi 开源项目（带链接）
- [ ] 致谢 OldPhonePreservation（MIDI 资源）
- [ ] LICENSE 全文链接
- [ ] 入口：PauseDialog / GameOverDialog 的"关于"按钮

## 技术要点

- `AlertDialog` 或独立页面
- 链接用 `LocalUriHandler`

## 产出物

- `ui/screen/AboutScreen.kt`
- 修改 `ui/screen/PauseDialog.kt` / `GameOverDialog.kt` 加入口