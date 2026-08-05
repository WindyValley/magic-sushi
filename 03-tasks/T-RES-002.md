# T-RES-002: 借用 EXL 音效 OGG

**状态：** ✅ done
**优先级：** P1
**预估：** 2h
**Owner：** main agent
**依赖：** -
**可并行：** T-CORE-008, T-RES-001

## 目标

从 EXL 项目提取 4 种音效（交换/消除/连锁/滴答），转换为 OGG 放入 `res/raw/`。

## 来源

- 需求：FR-7.1 ~ FR-7.4（4 种音效）
- 设计：references/EXL-Magic-Sushi.md

## 验收标准

- [x] 4 种音效 OGG 文件存在于 `res/raw/`
- [x] 命名：`sfx_swap.ogg` / `sfx_match.ogg` / `sfx_combo.ogg` / `sfx_tick.ogg`
- [x] 音量合理（不爆音）
- [x] 长度 < 5 秒（避免拖沓；swap 2.49s 略超 2s 目标但仍可接受）

## 技术要点

- EXL 是 MIDI，需要 MIDI → OGG 转换
- 工具：FFmpeg + SoundFont
- 或直接找开源 OGG 资源（更简单）

## 产出物

- `app/src/main/res/raw/sfx_swap.ogg`
- `app/src/main/res/raw/sfx_match.ogg`
- `app/src/main/res/raw/sfx_combo.ogg`
- `app/src/main/res/raw/sfx_tick.ogg`

## 实际映射（2026-06-20 12:30 by subagent-D）

EXL 仓库 `Assets/` 目录有 4 个 OGG 资源，对应 SDL2 源码中的 4 个音频槽（`Magic-Sushi-SDL2.c:68-71`）：

| 资源名 | 用途（SDL2 源） | → 我们的命名 | 长度 | 大小 |
|--------|----------------|------------|------|------|
| `gx_magicsushi_move.ogg` | SOUND_MOVE | **sfx_swap.ogg** | 2.49s | 20.8KB |
| `gx_magicsushi_select.ogg` | SOUND_SELECT | **sfx_match.ogg** | 0.22s | 4.2KB |
| `gx_magicsushi_bgm.ogg` | MUSIC_BACKGROUND | _(未使用)_ | 7.63s | 61KB |
| `gx_magicsushi_timeout.ogg` | MUSIC_GAMEOVER | _(未使用)_ | 4.56s | 36KB |

EXL 项目**没有** combo 和 tick 音效，所以这 2 个用 FFmpeg `lavfi sine` 合成占位音：
- `sfx_combo.ogg`：C5→E5→G5→C6 上升琶音，1.14s，15.5KB（占位）
- `sfx_tick.ogg`：1000Hz 短促 blip，0.08s，3.6KB（占位）

后续可替换为更拟真的素材（例如 freesound.org）。

## 备注

- P1 优先级，没有音效也能跑（FR-7 是 P2）
- 但 EXL 有现成资源，借用成本低