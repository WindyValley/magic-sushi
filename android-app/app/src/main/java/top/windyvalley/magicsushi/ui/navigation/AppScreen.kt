package top.windyvalley.magicsushi.ui.navigation

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
 * 存在 `MainActivity` 的 `remember` 里，不单独建 AppViewModel ——
 * 屏幕切换是纯 UI 关注点，且「进程死亡后回到菜单」是合理行为。
 * 若以后要跨进程恢复到原屏幕，把 `remember` 换成 `rememberSaveable` 即可
 * （`data object` 天然可省，无需自定义 Saver）。
 */
sealed interface AppScreen {

    /** 开始界面：【开始游戏】【历史记录】【退出游戏】。启动落地屏。 */
    data object Menu : AppScreen

    /** 游戏主屏。 */
    data object Game : AppScreen

    /** 历史记录列表。 */
    data object History : AppScreen
}
