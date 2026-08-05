# Magic Sushi 技术选型对比表

> 对应 02-design.md §2

---

## 触控交互

### ADR-001 决策

| 维度 | 方案 A：PointerInputScope ✅ | 方案 B：Custom View | 方案 C：GestureDetector |
|------|------|------|------|
| Compose 集成 | ✅ 原生 | ❌ 需 AndroidView | ⚠️ 可用 |
| 拖动精度 | ✅ Offset 实时 | ✅ 高 | ⚠️ 一般 |
| 复杂度 | 中 | 高 | 低 |
| **结论** | **采用** | 放弃 | 备选 |

---

## 状态管理

### ADR-002 决策

| 维度 | 方案 A：ViewModel+StateFlow ✅ | 方案 B：Compose State | 方案 C：Redux |
|------|------|------|------|
| 可测试性 | ✅ | ⚠️ | ✅ |
| 复杂度 | 中 | 低 | 高 |
| 多人协作 | ✅ | ⚠️ | ✅ |
| **结论** | **采用** | 放弃 | 过渡方案 |

---

## 动画引擎

### ADR-003 决策

| 维度 | 方案 A：Compose Animation ✅ | 方案 B：Lottie | 方案 C：ObjectAnimator |
|------|------|------|------|
| APK 大小 | ✅ 0增量 | ⚠️ +1.5MB | ⚠️ +200KB |
| Compose 集成 | ✅ 原生 | ⚠️ 需桥接 | ❌ 需桥接 |
| 手势驱动 | ✅ 天然 | ❌ 不支持 | ⚠️ 需手动 |
| **结论** | **采用** | 放弃 | 备选 |

---

## 棋盘渲染

| 维度 | 方案 A：CanvasComposable ✅ | 方案 B：LazyVerticalGrid + Image | 方案 C：Table + Image |
|------|------|------|------|
| 绘制性能 | ✅ 高（硬件加速） | ⚠️ 中（Compose 重组开销） | ⚠️ 低（Table 开销大） |
| 动画集成 | ✅ 直接在 Canvas 里画动画 | ⚠️ 需要协调多个 Image | ❌ 不适合 |
| 触摸命中 | ✅ `drawBehind` 精确坐标 | ⚠️ 需要 `Modifier.clickable` | ⚠️ 需要计算 |
| 代码复杂度 | 中 | 低 | 低 |
| **结论** | **采用** | 备选（简单棋盘可考虑） | 放弃 |

---

## 素材格式

| 维度 | 方案 A：PNG（借用 EXL）✅ | 方案 B：SVG/矢量 | 方案 C：WebP |
|------|------|------|------|
| 来源 | EXL/Magic-Sushi 直接可用 | 需转换 | 需转换 |
| 加载速度 | ✅ 快 | ⚠️ 需解析 | ✅ 优于 PNG |
| APK 大小 | ⚠️ 6种×2状态≈12张 | ✅ 更小 | ✅ 优于 PNG |
| 缩放质量 | ⚠️ 需要多套密度 | ✅ 矢量无限 | ✅ 优于 PNG |
| **结论** | **采用**（MVP 优先） | v2 优化考虑 | v2 优化考虑 |

---

## 全部选型汇总

| 层级 | 选型 | 备选 |
|------|------|------|
| **语言** | Kotlin 1.9+ | - |
| **UI 框架** | Jetpack Compose（BOM 2024.02+） | - |
| **核心逻辑** | 纯 Kotlin（无 UI 依赖） | - |
| **NDK** | ❌ 不上（v1.0 MVP） | v2.0 再考虑 |
| **触控交互** | PointerInputScope + detectDragGestures | GestureDetector 备选 |
| **状态管理** | ViewModel + StateFlow + MutableStateFlow | - |
| **动画引擎** | Compose Animation API | Lottie（v2 增强） |
| **棋盘渲染** | CanvasComposable（`drawCanvas`） | - |
| **素材格式** | PNG（来自 EXL 项目） | SVG（v2） |
| **音效格式** | MIDI → OGG（来自 EXL 项目） | - |
| **本地存储** | SharedPreferences（最高分） | DataStore（v2） |
| **最低 SDK** | API 26（Android 8.0） | - |
| **目标 SDK** | API 34（Android 14） | - |
| **包名** | `top.windyvalley.magicsushi` | - |