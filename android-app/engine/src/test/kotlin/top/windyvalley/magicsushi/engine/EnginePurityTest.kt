package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎层纯净度守卫。
 *
 * ## 为什么这个测试几乎不可能失败 —— 而它恰恰是重点
 *
 * `:engine` 是纯 Kotlin JVM module，classpath 上**根本没有 android.jar**。
 * 写下 `import android.util.Log` 会直接编译失败，测试都跑不到。
 * 也就是说：约定由**编译器**保证，不靠人自觉，也不靠这个测试。
 *
 * 那这个测试的价值在哪？**防止有人"顺手"把约定改掉**：
 *
 * - 有人在 `engine/build.gradle.kts` 里把 `kotlin("jvm")` 换成
 *   `com.android.library`（比如"就想用一下 Log 调试"）
 * - 有人加了 `implementation("androidx.annotation:annotation")`
 *   这类看着无害、实则把 Android 类型引入公开签名的依赖
 *
 * 这两种改动都能让上面那行 import 编译通过，而此测试会立刻变红，
 * 附带说明为什么不该那么做。它守的是**构建配置**，不是源码。
 *
 * ## 与 ADR 的关系
 *
 * 引擎层零 Android 依赖不是洁癖，是为了：
 * 1. 逻辑可在 JVM 上以毫秒级速度测试（111 个测试跑完约 1 秒，无需模拟器）
 * 2. 引擎可复用到服务端 / CLI / 桌面 / KMP 目标
 * 3. 消除"UI 状态漏进游戏逻辑"这类耦合的物理可能性
 */
class EnginePurityTest {

    @Test
    fun `引擎 module 的 classpath 上不应存在 Android 类`() {
        // 若有人把 :engine 改成 com.android.library，这些类会变得可加载。
        val androidClasses = listOf(
            "android.util.Log",
            "android.content.Context",
            "android.os.Bundle",
            "androidx.annotation.NonNull",
        )

        val leaked = androidClasses.filter { fqcn ->
            try {
                Class.forName(fqcn, false, this::class.java.classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: NoClassDefFoundError) {
                false
            }
        }

        assertTrue(
            """
            引擎层的 Android 依赖泄漏了：$leaked

            :engine 必须保持纯 Kotlin JVM module（kotlin("jvm")）。
            检查 engine/build.gradle.kts 是否被改成了 com.android.library，
            或是否新增了携带 Android 类型的依赖。

            需要日志请注入接口，不要直接用 android.util.Log。
            """.trimIndent(),
            leaked.isEmpty(),
        )
    }

    @Test
    fun `引擎的核心类型可在纯 JVM 环境下实例化`() {
        // 这是上面那个测试的正面表述：不只"没有 Android"，
        // 而是"没有 Android 也能完整工作"。
        // 若某个引擎类型偷偷依赖了 Android runtime，这里会抛异常。
        val board = BoardEngine.generateInitialBoard(seed = 42L)
        val matches = MatchEngine.detectMatches(board)
        val state = GameState(board = board)

        assertTrue("初始棋盘应为 7x7", board.size == 7)
        assertTrue("初始棋盘不应有预存三连", matches.isEmpty())
        assertTrue("GameState 应可在纯 JVM 下构造", state.presentation is BoardPresentation.Stable)
    }
}
