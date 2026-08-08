package top.windyvalley.magicsushi.ui.navigation

import androidx.compose.runtime.saveable.Saver

/**
 * 应用级屏幕状态。
 *
 * ## 为什么手写而不用 navigation-compose
 *
 * 全应用只有 3 个静态屏幕，彼此之间**无参数传递、无深链、无返回栈需求**。
 * navigation-compose 的核心价值是路由字符串 + 参数序列化 + 返回栈管理 ——
 * 这里一个都用不上，引进来只是多一层 NavHost 的间接和一个依赖。
 *
 * 一个 `sealed interface` + `when` 就够，且 `when` 是穷尽的：将来加屏幕时
 * 编译器会在每个分发点报错，不会像路由字符串那样运行时才发现拼错。
 *
 * ## 演进条件
 *
 * 若将来出现下列任一需求，再换 navigation-compose 才划算：
 * 关卡选择（需要带参数导航）、设置页的多级子页（需要返回栈）、
 * 分享深链（需要 URI 路由）。
 *
 * ## 状态存放在哪
 *
 * 存在 `MainActivity` 的 `rememberSaveable` 里（配 [AppScreenSaver]），
 * 不单独建 AppViewModel —— 屏幕切换是纯 UI 关注点。
 *
 * ⚠️ `data object` **不能**直接交给 `rememberSaveable`。曾经这么写过，
 * 结果是冷启动即崩（见 [AppScreenSaver] 的说明）。
 */
sealed interface AppScreen {

    /**
     * 持久化标识。
     *
     * 刻意不用 `toString()` 或 `this::class.simpleName` —— 那样类名就成了
     * 存储格式的一部分，将来重命名屏幕会静默改变已存档的值。这里的字符串
     * 是显式的、与类名解耦的协议。
     */
    val key: String

    /** 开始界面：【开始游戏】【历史记录】【退出游戏】。启动落地屏。 */
    data object Menu : AppScreen {
        override val key: String = "menu"
    }

    /** 游戏主屏。 */
    data object Game : AppScreen {
        override val key: String = "game"
    }

    /** 历史记录列表。 */
    data object History : AppScreen {
        override val key: String = "history"
    }

    companion object {
        /**
         * 由 [key] 还原屏幕。无法识别时退回 [Menu]。
         *
         * 容错而非抛异常：这条路径只在进程被系统杀死后恢复时走到，
         * 为一个「记不清玩家在哪个屏」的问题让 App 起不来是本末倒置。
         */
        fun fromKey(key: String): AppScreen = when (key) {
            Game.key -> Game
            History.key -> History
            else -> Menu
        }
    }
}

/**
 * [AppScreen] 的 `rememberSaveable` Saver —— 存 [AppScreen.key]（`String`）。
 *
 * ## 为什么必须有它
 *
 * 曾经的写法是直接 `rememberSaveable { mutableStateOf(AppScreen.Menu) }`，
 * 结果**冷启动即崩**：
 *
 * ```
 * java.lang.IllegalArgumentException: MutableState containing Menu cannot be
 * saved using the current SaveableStateRegistry. The default implementation
 * only supports types which can be stored inside the Bundle.
 *     at androidx.compose.runtime.saveable.SaveableHolder.register(RememberSaveable.kt:182)
 *     at androidx.compose.runtime.saveable.SaveableHolder.onRemembered(RememberSaveable.kt:193)
 * ```
 *
 * 注意崩溃点是 `onRemembered` 而**不是** `onSaveInstanceState`：Compose 在
 * 首次 composition 注册 saver 时就立即校验类型，所以活不过第一帧 ——
 * 这不是「切后台才复现」的边角问题。
 *
 * 默认 saver 只接受能进 Bundle 的类型，白名单是
 * `Serializable / Parcelable / String / SparseArray / Binder / Size / SizeF`
 * （见 `DisposableSaveableStateRegistry.android.kt` 的 `AcceptableClasses`）。
 * `data object` 一个都不沾。
 *
 * ⚠️ 曾经的注释断言「`data object` 天然可省，无需自定义 Saver」——**这是错的**。
 * `data object` 的单例性质保证的是「恢复后 `===` 仍成立」，
 * 与「能否写入 Bundle」是两个互不相干的问题。
 *
 * ## 为什么存 String 而不让 AppScreen 实现 Parcelable
 *
 * 实现 `Parcelable` 要引 `kotlin-parcelize` 插件。为三个无状态 object 加一个
 * 编译插件不划算，而 `String` 本身就在白名单里。
 */
val AppScreenSaver: Saver<AppScreen, String> = Saver(
    save = { it.key },
    restore = { AppScreen.fromKey(it) },
)
