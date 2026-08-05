# T-CORE-007: 核心层单元测试（>80% 覆盖）

**状态：** ✅ done
**优先级：** P0
**预估：** 8h
**Owner：** main agent
**依赖：** T-CORE-004, T-CORE-005, T-CORE-006
**可并行：** -

## 目标

为核心层所有 Engine 写单元测试，覆盖率 >80%。

## 来源

- 需求：NFR-4.1（核心游戏逻辑单元测试覆盖率 >80%）
- 设计：02-design.md §NFR

## 验收标准

- [ ] JUnit 5 测试框架
- [ ] 每个 Engine 至少 5 个测试用例
- [ ] 覆盖：正常路径 + 边界 + 异常
- [ ] 覆盖率报告：> 80% line coverage
- [ ] CI 可运行（`./gradlew test`）

## 技术要点

- 用 `kotest` 或 `JUnit5`
- 用例覆盖：
  - BoardEngine：初始填充 100 次随机种子、交换相邻、非相邻
  - MatchEngine：横/竖三连、4/5 连、斜线不识别、L 形
  - GravityEngine：单列下落、多列、满列
  - CascadeEngine：单次/多次连锁、兜底
  - ScoreEngine：基础分、combo 加成、4/5 连
  - TimerEngine：5 个边界（ADR-004 列出）

## 产出物

- 测试代码：`test/engine/*Test.kt`（6 个文件）
- 配置：`build.gradle.kts` 添加 test dependencies

## 备注

- 核心层无 Android 依赖，测试可以纯 JVM 跑
- 这是 80% 覆盖率的关键任务