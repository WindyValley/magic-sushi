package top.windyvalley.magicsushi.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppScreen 的持久化标识与 Saver 往返测试。
 *
 * ## 为什么这个测试值得存在
 *
 * 批次 C 曾把 `data object` 直接交给 `rememberSaveable`，导致**冷启动即崩**：
 *
 * ```
 * IllegalArgumentException: MutableState containing Menu cannot be saved using
 * the current SaveableStateRegistry.
 * ```
 *
 * 当时 155 个单测全绿 —— 因为没有任何一个测试碰到导航状态的序列化。
 * 这里补上的就是那道防线：Saver 的 save/restore 是纯函数，不需要
 * Android 运行时就能验证。
 *
 * ⚠️ 本测试**不能**替代真机验证。它锁的是「key 往返正确」，
 * 锁不住「Compose 是否接受这个类型」—— 后者只有真机/模拟器能验。
 */
class AppScreenSaverTest {

    /** 所有屏幕，供遍历用。新增屏幕时这里会漏 —— 见 allScreensAreCovered。 */
    private val allScreens =
        listOf(AppScreen.Menu, AppScreen.Game, AppScreen.History, AppScreen.Settings)

    @Test
    fun `每个屏幕的 key 都能往返还原为同一单例`() {
        allScreens.forEach { screen ->
            val restored = AppScreen.fromKey(screen.key)
            // 用 assertSame 而非 assertEquals：data object 是单例，
            // 恢复后必须是同一个实例，否则 `when` 的引用比较会出意外。
            assertSame("key=${screen.key} 往返后不是同一实例", screen, restored)
        }
    }

    @Test
    fun `key 互不重复`() {
        val keys = allScreens.map { it.key }
        assertEquals("存在重复 key，会导致两个屏幕无法区分", keys.size, keys.toSet().size)
    }

    @Test
    fun `未知 key 退回 Menu 而不抛异常`() {
        // 这条路径在「屏幕被重命名后用户从旧存档恢复」时会走到。
        // 崩溃比回到菜单糟糕得多。
        assertSame(AppScreen.Menu, AppScreen.fromKey("unknown"))
        assertSame(AppScreen.Menu, AppScreen.fromKey(""))
        assertSame(AppScreen.Menu, AppScreen.fromKey("Game"))  // 大小写敏感，不匹配
    }

    @Test
    fun `key 不是类名 —— 重命名类不应改变存储格式`() {
        // 若哪天有人把 key 改成 this::class.simpleName，这个测试会红。
        // 类名一旦成为存储格式的一部分，重构就会静默破坏已存档的值。
        assertEquals("menu", AppScreen.Menu.key)
        assertEquals("game", AppScreen.Game.key)
        assertEquals("history", AppScreen.History.key)
        assertEquals("settings", AppScreen.Settings.key)
    }

    @Test
    fun `key 都是可写入 Bundle 的 String`() {
        // 崩溃的根因就是类型不在 Bundle 白名单里。
        // String 在白名单中（Serializable/Parcelable/String/SparseArray/
        // Binder/Size/SizeF），所以存 key 是安全的。
        allScreens.forEach { screen ->
            @Suppress("USELESS_IS_CHECK")
            assertTrue("key 必须是 String", screen.key is String)
            assertTrue("key 不能为空", screen.key.isNotEmpty())
        }
    }

    @Test
    fun `allScreens 覆盖了 sealed interface 的全部实现`() {
        // 手写清单会漏。这里用穷尽 when 让编译器兜底：
        // 新增 AppScreen 成员时，下面的 when 会编译失败，
        // 提醒把新屏幕加进 allScreens 和 fromKey。
        allScreens.forEach { screen ->
            val covered: Boolean = when (screen) {
                AppScreen.Menu -> true
                AppScreen.Game -> true
                AppScreen.History -> true
                AppScreen.Settings -> true
            }
            assertTrue(covered)
        }
        assertEquals("allScreens 数量与已知屏幕不一致", 4, allScreens.size)
    }
}
