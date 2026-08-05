# T-DATA-001: PrefsRepository 最高分读写

**状态：** ✅ done
**优先级：** P0
**预估：** 3h
**Owner：** main agent
**依赖：** T-ANDROID-002
**可并行：** T-AUDIO-001

## 目标

封装 SharedPreferences，提供最高分读写接口。

## 来源

- 需求：FR-5.4（历史最高分本地保存）
- 设计：02-design.md §2.2（PrefsRepository）

## 验收标准

- [x] `PrefsRepository(context)` class
- [x] `getHighScore(): Int`
- [x] `saveHighScore(score: Int)`
- [x] `isMuted(): Boolean`
- [x] `setMuted(muted: Boolean)`
- [x] 用 `Context.dataStore` 或 `SharedPreferences`
- [ ] 单测：使用 Robolectric 或纯 mock（v2）

## 技术要点

- 推荐用 `DataStore<Preferences>`（替代 SharedPreferences）
- 但 MVP 也可用 SharedPreferences（更简单）
- 异步操作：`Flow<Int>` 暴露最高分

## 产出物

- `data/PrefsRepository.kt`

## 备注

- DataStore 是 v2 优化方向，MVP 用 SharedPreferences 即可