# EXL/Magic-Sushi 参考笔记

> 把 MTK 功能机《魔法寿司》移植到 SDL1/SDL2/Web 的开源项目
> 我们借用其素材（寿司图片、音效），但代码不直接复用（自己重写）

## 项目地址

- GitHub: https://github.com/EXL/Magic-Sushi
- 可玩游戏: https://lab.exlmoto.ru/sushi/
- 真机演示: https://www.youtube.com/watch?v=NHHZTvMguP4

## 关键特性

- **平台：** SDL1 / SDL2 / Web (Emscripten)
- **支持构建：**
  - Linux (Ubuntu)
  - Windows (MSYS2 MinGW)
  - Web (WASM)
- **依赖：**
  - SDL2 + SDL2_image + SDL2_mixer（图形 + 图片 + 音效）
- **代码来源：** 原 MTK 资源被 [@nehochupechatat](https://github.com/nehochupechatat) 提取，音效来自 [@OldPhonePreservation](https://twitter.com/oldphonepreserv)

## 我们的用法

✅ **借用：**
- 寿司图片（PNG 格式）
- 背景图片
- 音效（MIDI 格式）
- 字体（如果有）

❌ **不复用：**
- C/C++ 代码（我们要重写）
- SDL 框架（我们用 Compose）

## 借用素材的步骤

1. 克隆 EXL/Magic-Sushi 仓库到本地
2. 检查 LICENSE（确认素材可商用/可借用）
3. 提取 `Images/` 目录下的寿司 PNG
4. 提取 `Sounds/` 目录下的 MIDI
5. 转换格式（如需）放入我们 Android 工程的 `res/drawable/` 和 `res/raw/`

## 注意事项

- 借用的素材**仅限本项目使用**，不要二次分发
- 在 App 的 About 页面注明素材来源（致谢 EXL 和原作者）
- LICENSE 文件随项目一起发布