package top.windyvalley.magicsushi.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.windyvalley.magicsushi.ui.theme.SushiBgDark

/**
 * 关于页：项目信息与版权声明。
 *
 * ## 内容来源
 *
 * 法律文本**逐字取自仓库根目录的 `NOTICE.md`**，不在这里另行措辞。
 *
 * 这是刻意的：版权声明是同一份事实的两处表达，一处改了另一处没跟上就会
 * 出现「App 里说保留所有权利、仓库里写着 MIT」之类的自相矛盾 —— 而法律
 * 文本的矛盾比功能 bug 更麻烦。改动时必须两处同步，本文件的每个段落都在
 * 注释里标了对应的 NOTICE.md 章节。
 *
 * ## 为什么不直接读 NOTICE.md
 *
 * 把它打进 assets 再运行时解析 markdown，需要引一个 markdown 渲染库，
 * 或者手写一个够用的子集解析器。为一屏静态文本引依赖不划算，而手写解析
 * 会引入新的出错面（渲染错了比写死更难发现）。
 *
 * 折中：文本写死在这里，靠注释锚定来源。同步责任交给人，但注释让「该同步」
 * 这件事在改动时可见。
 *
 * ## 为什么不是设置页的子页
 *
 * `AppScreen.kt` 顶部把「设置页的多级子页」列为该换 navigation-compose 的
 * 演进条件。关于页做成与设置**平级**的 `AppScreen.About`，从主菜单进入，
 * 就不触发那个条件 —— 手写 `when` 导航继续够用。
 *
 * @param versionName 版本号，由调用方从 `BuildConfig.VERSION_NAME` 传入。
 *                    不在这里直接读 BuildConfig —— 那会让这个 Composable
 *                    依赖构建配置，预览和测试都不好摆。
 * @param onBack      返回上一屏。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SushiBgDark)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary,
                    )
                }
                Text(
                    text = "关于",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ---- 正文 ----
            //
            // 必须可滚动：版权声明篇幅不短，短屏机型（或开了大字号）放不下。
            // 法律文本被裁掉看不见，等于没有声明。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                // ================= 标题区 =================
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Magic Sushi",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "三连成寿司",
                    color = TextSecondary,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "版本 $versionName",
                        color = Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ================= 项目信息 =================
                SectionHeading("项目信息")

                InfoRow("类型", "单人消除游戏")
                InfoRow("平台", "Android 8.0+")
                InfoRow("技术栈", "Kotlin + Jetpack Compose")

                // 开源地址做成可点的超链接，跳系统浏览器。
                LinkRow(
                    label = "开源地址",
                    display = "github.com/WindyValley/magic-sushi",
                    url = PROJECT_URL,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ================= 与原版的关系 =================
                // 对应 NOTICE.md 的「与原版的关系」章节。
                //
                // ⚠️ 措辞纪律：不能用「复刻」「克隆」「移植」「还原」——
                // 玩法规则由作者重新定义，与原版并不相同。用那些词是事实错误，
                // 且会加重素材授权问题上的观感。
                SectionHeading("与原版的关系")

                BodyText(
                    "本项目不是 MTK《魔法寿司》的复刻或移植，而是受其启发的" +
                        "重新设计。原版核心机制（关卡制、消除配额、时间奖励、" +
                        "难度递增）与本项目的实现并不相同，玩法规则由作者按" +
                        "个人偏好重新定义。"
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ================= 版权声明 =================
                // 对应 NOTICE.md 的「本项目不提供开源许可」+「非商用声明」。
                //
                // 这两段是本页存在的主要理由 —— 素材没有可追溯的授权，
                // 必须在应用内明示，不能只写在仓库里（玩家不看仓库）。
                SectionHeading("版权声明")

                // 用高亮卡片而非普通段落：这是全页最需要被读到的一段。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Accent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(2.dp)
                        .background(
                            color = SushiBgDark,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "保留所有权利 (All Rights Reserved)",
                        color = Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "本项目不提供开源许可，仅供学习、研究与技术交流使用。",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    BulletLine("不得用于任何商业用途", allowed = false)
                    BulletLine("不得二次分发本项目携带的美术与音效素材", allowed = false)
                    BulletLine("可以阅读代码、参考实现思路、在本地构建运行", allowed = true)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ================= 素材来源 =================
                // 对应 NOTICE.md 的「素材来源与版权状态」章节。
                //
                // ⚠️ 这一段的每个事实都经过核实，不要凭印象改：
                // EXL/Magic-Sushi 仓库本身没有 LICENSE 文件（默认保留所有
                // 权利），其素材源自 MTK 固件，该项目自身也不具备转授权资格。
                // 所以这里只能说「经该项目提取整理」，不能说「据其授权使用」。
                SectionHeading("素材来源")

                BodyText(
                    "寿司图片与音效来自 MTK 功能机《魔法寿司》固件资源，" +
                        "经 EXL/Magic-Sushi 项目提取整理。"
                )
                Spacer(modifier = Modifier.height(8.dp))
                BodyText(
                    "这些素材无公开授权，在本项目中属于未获授权的引用，" +
                        "仅在非商用、学习性质的前提下保留。若权利人提出异议，" +
                        "作者将立即移除相关素材。"
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ================= 致谢 =================
                // 对应 NOTICE.md 的「致谢」章节。
                SectionHeading("致谢")

                BodyText("MTK《魔法寿司》原作者 —— 玩法灵感来源")
                Spacer(modifier = Modifier.height(6.dp))
                BodyText("EXL/Magic-Sushi —— 素材提取与原版逻辑参考")
                Spacer(modifier = Modifier.height(6.dp))
                BodyText("代码部分（Kotlin 源码、构建脚本、文档）由作者原创。")

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// ============================================================================
// 私有小组件
//
// 刻意不复用 SettingsScreen 里的 SectionTitle —— 那个是 private，而把它
// 提成共享组件需要新建一个文件、定一套参数。两个屏幕各三十行样式代码，
// 重复的是「几个 sp 和颜色」这种最不易出错的部分，抽象的收益抵不过
// 多一层间接的代价。若第三个屏幕也要同样的分区标题，那时再抽。
// ============================================================================

/** 分区标题。与设置页的 SectionTitle 视觉一致（橙色、13sp、Bold）。 */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** 正文段落。行高放宽到 20sp —— 密排的小字读起来累。 */
@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
}

/** 「标签 —— 值」一行，标签左对齐、值右对齐。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「标签 —— 可点链接」一行。点击跳系统浏览器。
 *
 * ## 为什么不用 LinkAnnotation
 *
 * 那是 Compose BOM 2024.09+ 的 API（配 `AnnotatedString.fromHtml` 一起来的），
 * 本项目锁在 2024.02.00。升 BOM 只为一个链接不划算，且跨大版本升级会牵动
 * 一堆 Material3 的行为变化。
 *
 * 这里直接用 `clickable` + `Intent.ACTION_VIEW`：能力等价，无版本要求。
 *
 * ## 为什么不用 ClickableText
 *
 * `ClickableText` 在 1.6 已标记 deprecated，且它的价值在于「一段文字里只有
 * 部分可点」。这里整行都是链接，`Modifier.clickable` 更直接，还顺带拿到
 * 整行的点击热区（比只点中那几个字好按）。
 *
 * ## 找不到浏览器怎么办
 *
 * 用 `try/catch ActivityNotFoundException` 而**不是**先查询能不能开。
 *
 * ⚠️ 曾经写成 `queryIntentActivities(intent, 0).isNotEmpty()` 再决定要不要
 * `startActivity`，结果**链接永远点不动**：`targetSdk = 34` 且 manifest 里
 * 没有 `<queries>` 声明，API 30+ 的包可见性限制让这个查询返回空列表 ——
 * 即便系统装了 Chrome。实测模拟器 `pm query-activities` 能查到
 * `com.android.chrome`，但应用自己查得到的是空。
 *
 * `resolveActivity` 有同样的问题，同样不能用。
 *
 * 要让查询生效得在 manifest 加：
 *
 *     <queries><intent><action android:name="android.intent.action.VIEW" />
 *     <data android:scheme="https" /></intent></queries>
 *
 * 但那是为「查询」付的代价，而这里根本不需要查询 —— 直接 start 并捕获
 * 异常，行为完全等价且不用改 manifest。
 *
 * 真无浏览器时静默不响应，不弹 Toast 骚扰（关于页点链接失败不值得打断）。
 */
@Composable
private fun LinkRow(label: String, display: String, url: String) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    // 设备上没有能处理 https 的应用。极少见（精简 ROM、
                    // 企业设备禁用浏览器），静默忽略。
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.width(88.dp),
        )
        // 链接样式：强调色 + 下划线。
        //
        // 两者都要 —— 只靠颜色区分对色盲用户无效（约 8% 男性有红绿色弱），
        // 下划线是不依赖颜色感知的可点信号。
        Text(
            text = display,
            color = Accent,
            fontSize = 14.sp,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = OpenInNewIcon,
            contentDescription = "在浏览器中打开",
            tint = Accent,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 带符号的条目。
 *
 * ## 为什么用矢量图而不是字符
 *
 * 曾经用过 emoji（`❌`/`✅`）和基础符号（`×`/`√`）。两者都是**赌用户系统
 * 字体有对应字形** —— emoji 输了（`🔀` 在 API 34 模拟器上降级成橙底白 X
 * 方块，看着意思完全相反），换基础符号只是把赌注换小，没改变性质：
 * 定制 ROM、精简字体包、字体子集化都可能缺字形，而缺了就是豆腐块。
 *
 * `ImageVector` 是 APK 自带的路径数据，渲染不经过字体系统，任何设备一致。
 * 尺寸和颜色还能精确控制（字符受行高、基线、字重影响，对齐要反复试）。
 *
 * 用 `Icons.Filled.Check` / `Close` —— 它们在 `material-icons-core` 里，
 * 已随 material3 传递依赖进来（`ArrowBack` 就是同一批），**不增加依赖，
 * 不增加 APK 体积**。不需要 `material-icons-extended`（那个几千个图标）。
 *
 * 颜色也承担语义：禁止项用暖红，允许项用绿 —— 不只靠图形区分。
 */
@Composable
private fun BulletLine(text: String, allowed: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (allowed) Icons.Filled.Check else Icons.Filled.Close,
            // 图标已有颜色语义，且紧跟的文字自解释，重复念一遍是噪音。
            contentDescription = null,
            tint = if (allowed) AllowedGreen else ForbiddenRed,
            modifier = Modifier
                .size(15.dp)
                // 与首行文字的基线对齐 —— Top 对齐时图标会顶得偏高。
                .padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

// ============================================================================
// 配色
//
// 与 SettingsScreen 用同一组字面值（那边也是写死的 0xFFFFE8C5 等）。
// 没有提到 Theme 里是因为它们是「深色背景上的文字层级」这一局部约定，
// 不是全局设计 token；提上去反而要给它们起 MaterialTheme 语义名，
// 而 onSurface / onSurfaceVariant 之类的映射在这里并不贴切。
// ============================================================================

/** 主要文字：暖米色。 */
private val TextPrimary = Color(0xFFFFE8C5)

/** 次要文字：主色降到 60% —— 层级靠透明度而非另调一个颜色，不会跑色。 */
private val TextSecondary = Color(0x99FFE8C5)

/** 强调色：琥珀。与 TopToast 描边、设置页分区标题同一个值。 */
private val Accent = Color(0xFFFFB347)

/** 允许项：绿。取自 SushiTertiary。 */
private val AllowedGreen = Color(0xFF8BC34A)

/** 禁止项：暖红。比纯红柔和，配深棕底不刺眼。 */
private val ForbiddenRed = Color(0xFFE85D2F)

/**
 * 开源仓库地址。
 *
 * 与 README.md / NOTICE.md 里的链接是同一个事实的多处表达 —— 仓库改名或
 * 转移时三处都要跟上。这里单独提成常量，至少让 App 内部只有一处。
 */
private const val PROJECT_URL = "https://github.com/WindyValley/magic-sushi"

/**
 * 「在新窗口打开」图标，手写路径数据。
 *
 * ## 为什么手写
 *
 * `Icons.Filled.OpenInNew` 在 `material-icons-extended` 里，而项目只依赖
 * `material-icons-core`（随 material3 传递进来的那批，含 ArrowBack / Check /
 * Close 等约 48 个）。为一个图标引入 extended 不划算 —— 那个 AAR 有几千个
 * 图标，方法数和体积代价都不小（即便 R8 能裁，debug 包也会先胖起来）。
 *
 * 路径数据取自 Material Symbols 的 `open_in_new`（Apache-2.0，可自由使用），
 * 24x24 视口。
 *
 * ## 为什么不用字符 `↗`
 *
 * 同 [BulletLine] 的理由：字符赌系统字体，矢量图自带。这个图标虽小，
 * 但它是「这行可以点」的视觉信号，缺失成豆腐块比没有更糟。
 */
private val OpenInNewIcon: ImageVector = ImageVector.Builder(
    name = "OpenInNew",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.White)) {
        // 外框（缺右上角的方框）
        moveTo(19f, 19f)
        horizontalLineTo(5f)
        verticalLineTo(5f)
        horizontalLineTo(12f)
        verticalLineTo(3f)
        horizontalLineTo(5f)
        curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
        verticalLineTo(19f)
        curveTo(3f, 20.1f, 3.89f, 21f, 5f, 21f)
        horizontalLineTo(19f)
        curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
        verticalLineTo(12f)
        horizontalLineTo(19f)
        verticalLineTo(19f)
        close()
        // 右上角的外向箭头
        moveTo(14f, 3f)
        verticalLineTo(5f)
        horizontalLineTo(17.59f)
        lineTo(7.76f, 14.83f)
        lineTo(9.17f, 16.24f)
        lineTo(19f, 6.41f)
        verticalLineTo(10f)
        horizontalLineTo(21f)
        verticalLineTo(3f)
        horizontalLineTo(14f)
        close()
    }
}.build()
