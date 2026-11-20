package app.zhouzhang.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CJK 字阶。六档，够用且不可再增。
 *
 * 两条硬约束，缺一条界面就会「松」或「糊」：
 *
 * 1. letterSpacing 必须显式给 0。
 *    Compose 的 Text 不传 style 时继承 LocalTextStyle（M3 的 bodyLarge），
 *    那份默认带 0.5sp 字距——汉字被拉开后不成方块，看起来发虚。
 *    只改 fontSize 是改不掉这一项的，这也是旧版 13sp 文案行高 1.85 的原因。
 *
 * 2. lineHeight 必须显式给。
 *    中文按 1.4–1.5 倍给；金额、标题可以更紧。默认 24sp 会让 13sp 文案上下各多出 5dp。
 *
 * 另外全局开 tnum（等宽数字）：金额、百分比、日期在翻周时不会横向抖动。
 */
object ZType {
    private const val TNUM = "tnum"

    private fun s(
        size: Int,
        lh: Int,
        weight: FontWeight = FontWeight.Normal,
        tracking: Double = 0.0,
    ) = TextStyle(
        fontSize = size.sp,
        lineHeight = lh.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
        fontFeatureSettings = TNUM,
    )

    /** 本周金额。整屏唯一的 40sp */
    val display = s(40, 44, FontWeight.SemiBold, -0.02)

    /** 首次运行的品牌标题 */
    val displaySmall = s(26, 32, FontWeight.SemiBold, -0.01)

    /** 页面标题、抽屉标题 */
    val title = s(17, 24, FontWeight.SemiBold)

    /** 区块标题、按钮文案 */
    val titleSmall = s(15, 22, FontWeight.Medium)

    /** 正文 */
    val body = s(15, 22)

    /** 正文（小）：商户名、列表主行 */
    val bodySmall = s(14, 21)

    /** 副标题、说明 */
    val secondary = s(13, 19)

    /** 时间、百分比、单位 */
    val caption = s(12, 17)

    /** 徽章。只允许用在这里 */
    val micro = s(11, 15, FontWeight.Medium, 0.02)
}
