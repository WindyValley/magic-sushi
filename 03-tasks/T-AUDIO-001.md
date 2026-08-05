# T-AUDIO-001: SoundPlayer 音效封装

**状态：** ✅ done
**优先级：** P1
**预估：** 3h
**Owner：** main agent
**依赖：** T-RES-002, T-ANDROID-002
**可并行：** T-DATA-001

## 目标

封装音效播放（SoundPool），提供 playSwap/playMatch/playCombo/playTick 接口。

## 来源

- 需求：FR-7.1 ~ FR-7.4（4 种音效）
- 设计：references/EXL-Magic-Sushi.md

## 验收标准

- [ ] `SoundPlayer(context)` class
- [ ] `playSwap()` / `playMatch()` / `playCombo()` / `playTick()`
- [ ] 静音开关支持（不播放）
- [ ] 用 `SoundPool` 加载 OGG
- [ ] 提前预加载所有音效

## 技术要点

- `SoundPool.Builder()` + `load()`
- 4 种音效对应 4 个 soundId
- `play(soundId, 1f, 1f, 1, 0, 1f)`

## 产出物

- `audio/SoundPlayer.kt`

## 备注

- P1 优先级，没有音效也能玩
- 但 EXL 有现成资源，借用成本低