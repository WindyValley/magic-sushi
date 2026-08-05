# T-RES-001: 借用 EXL 寿司 PNG 素材

**状态：** ✅ done
**优先级：** P0
**预估：** 2h
**Owner：** main agent
**依赖：** -
**可并行：** T-CORE-008, T-RES-002

## 目标

从 EXL/Magic-Sushi 项目提取 6 种寿司 PNG，放入 Android 工程的 `res/drawable/`。

## 来源

- 需求：FR-1.4（寿司图片来源：EXL/Magic-Sushi）
- 设计：02-design.md §6 风险（R-001 LICENSE 审查）
- 参考：references/EXL-Magic-Sushi.md

## 验收标准

- [ ] 6 种寿司 PNG 文件存在于 `res/drawable/`
- [ ] 命名规范：`sushi_1.png` ~ `sushi_6.png`
- [ ] 多密度版本：mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi
- [ ] LICENSE 文件随项目发布
- [ ] About 页面致谢 EXL（待 T-ENH-003）

## 技术要点

- 用 `git clone https://github.com/EXL/Magic-Sushi`
- 检查 `Images/` 目录
- 提取后删除不需要的背景
- 用 Android Studio Image Asset Studio 或手动放置

## 产出物

- `app/src/main/res/drawable/sushi_1.png` ~ `sushi_6.png`
- 多密度版本 `drawable-mdpi/` `drawable-hdpi/` 等
- `app/src/main/res/values/strings.xml`（中文）

## 备注

- 风险 R-001：LICENSE 必须审查
- v2 可考虑矢量化（SVG）