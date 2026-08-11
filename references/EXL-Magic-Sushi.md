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

## 原版玩法（`Magic-Sushi-Engine.c` 源码调研，2026-08-11）

> 我们的实现与原版差异很大。记录在此以备将来做难度系统时参考。

### 初始状态（`mmi_gx_magicsushi_enter_game`，2523-2527 行）

```c
level       = 1     // 关卡
remainder   = 50    // 本关剩余消除配额
total_time  = 50    // 本关总时长（秒）
remain_time = 50    // 当前剩余时间
```

### 关卡制

| 机制 | 实现 |
|---|---|
| 过关条件 | `remainder <= 0`（消除 50 个） |
| 过关处理 | `level++`；`remainder = 50`；**`total_time -= 5`** |
| 难度递增 | `level > 4` → `type_num = 8`（寿司种类 6 → 8） |
| 死局 | `!mmi_gx_magicsushi_is_moremove()` → 重排 |

**关卡越高，本关总时长越短** —— 这是原版的难度曲线。

### 时间：奖励 + 封顶（不是重置）

```c
remain_time += 3;                    // 普通消除（1106、1179 行）
remain_time += total_time / 10;      // 连锁消除（2872、2893 行）

if (remain_time > total_time)        // 封顶（854-856 行，渲染时执行）
    remain_time = total_time;
```

封顶值是**本关总时长**，随关卡递减。第 1 关连锁加 `50/10 = 5` 秒，
第 2 关只加 `45/10 = 4` 秒。

### 计分

```c
game_grade += count * level;         // 普通消除（1103 行）
game_grade += 5 * level;             // 特殊消除（1177 行）
game_grade += level * bonus;         // 连锁（2870、2891 行）
```

**关卡数本身就是分数乘数**，没有长度倍数概念。

### 与本项目的差异

| 机制 | MTK 原版 | 本项目 |
|---|---|---|
| 模式 | 关卡制 + 配额 50 | 无限模式 |
| 时间 | 起始 50 秒，消除 +3，封顶=本关总时长 | 起始 60 秒，消除**重置**回 60 |
| 时间趋势 | 每关 -5 秒，越来越紧 | 恒定 |
| 计分 | `个数 × 关卡` | `10 × 个数 × 长度倍数 × 连锁倍数` |
| 种类 | 6 种，第 5 关起 8 种 | 固定 6 种 |
| 死局 | 自动重排 | 未实现 |

结论：本项目**不是复刻**，是受启发的重新设计。项目定位已据此修正。

---

## 授权状态 ⚠️

```
EXL/Magic-Sushi   license = none
```

**该仓库没有 LICENSE 文件**，默认保留所有权利。且其素材源自 MTK 固件，
EXL 自身也不具备转授权资格 —— 本笔记第 38 行「检查 LICENSE（确认素材可
商用/可借用）」这一步**无法完成，因为不存在可查的 LICENSE**。

本项目已据此移除 MIT LICENSE，改为保留所有权利 + 非商用声明，见
[NOTICE.md](../NOTICE.md)。

---

## 注意事项

- 借用的素材**仅限本项目使用**，不要二次分发
- 在 App 的 About 页面注明素材来源（致谢 EXL 和原作者）
- 版权声明随项目一起发布（见 `NOTICE.md`）