package app.zhouzhang.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义色令牌。
 *
 * 不直接用 MaterialTheme.colorScheme 承载：Material 的角色名
 * （primary / surfaceVariant / outline）表达不了「涨」「跌」「琥珀填充 vs 琥珀文字」
 * 这几组本 App 特有的语义，硬套会让调用点写着 `primary` 却在表示「超支」。
 *
 * 所以颜色分两层：
 *   ZColors —— 唯一的真值来源，浅色 / 深色各一份；
 *   Ink     —— 读取入口（`Ink.Text`），内部读 CompositionLocal。
 *              换成深色模式时全部调用点自动跟着变，不需要逐个改。
 */
@Immutable
data class ZColors(
    /** 页面底色。浅色是暖纸，深色是近黑的暖墨 */
    val bg: Color,
    /** 卡片 / 面板底色 */
    val surface: Color,
    /** 凹槽：进度条轨道、分段控件底 */
    val sunk: Color,
    /** 卡片描边（1dp）。替代阴影，纸感更真也省合成层 */
    val border: Color,
    /** 列表分隔细线 */
    val hairline: Color,
    /** 主文字 */
    val text: Color,
    /** 次要文字：正文级，必须 ≥ 4.5:1 */
    val text2: Color,
    /** 标注文字：时间、百分比、单位，必须 ≥ 4.5:1 */
    val text3: Color,
    /** 琥珀文字（链接、强调）。可读版，不是品牌原色 */
    val accent: Color,
    /** 琥珀填充（柱状图、进度条、今日标记）。不得承载小字 */
    val accentFill: Color,
    /** 压在 accentFill 上的文字 */
    val onAccent: Color,
    /** 涨 / 超支 */
    val up: Color,
    val upBg: Color,
    /** 跌 / 省下 */
    val down: Color,
    val downBg: Color,
    /** 柱状图非今日、非零的柱 */
    val barIdle: Color,
)

val LightColors = ZColors(
    bg = Color(0xFFF7F5F0),
    surface = Color(0xFFFFFFFF),
    sunk = Color(0xFFF0EDE5),
    border = Color(0xFFD5CCB8),
    hairline = Color(0xFFE3DCCD),
    text = Color(0xFF171B21),
    text2 = Color(0xFF5A6069),
    text3 = Color(0xFF676D75),
    accent = Color(0xFFA25A09),
    accentFill = Color(0xFFD9821B),
    onAccent = Color(0xFF2A1A04),
    up = Color(0xFFB3352A),
    upBg = Color(0xFFFBEEEC),
    down = Color(0xFF2C6E52),
    downBg = Color(0xFFEDF5F1),
    barIdle = Color(0xFFCFC7B6),
)

/**
 * 深色不是把浅色反过来。
 * 浅色靠「白卡压纸底」分层，深色下这个手法失效（1.10:1），
 * 所以卡面提亮一档、描边加重，层次改由「亮度差 + 描边」建立。
 */
val DarkColors = ZColors(
    bg = Color(0xFF14161A),
    surface = Color(0xFF1C1F25),
    sunk = Color(0xFF24282F),
    border = Color(0xFF2C3138),
    hairline = Color(0xFF23272E),
    text = Color(0xFFEDE9E0),
    text2 = Color(0xFFA2A8B2),
    text3 = Color(0xFF8B9199),
    accent = Color(0xFFF2A93B),
    accentFill = Color(0xFFF2A93B),
    onAccent = Color(0xFF2A1A04),
    up = Color(0xFFE8705F),
    upBg = Color(0xFF3B2222),
    down = Color(0xFF6FBF9A),
    downBg = Color(0xFF1E3329),
    barIdle = Color(0xFF3A3F48),
)

val LocalZColors = staticCompositionLocalOf { LightColors }

/**
 * 颜色读取入口。全部为 @Composable 取值，只能用在 composable 上下文里。
 * 命名沿用旧的 Ink，是为了让迁移只发生在 Theme.kt 内部，调用点保持可读。
 */
object Ink {
    val Bg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.bg
    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.surface
    val Sunk: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.sunk
    val Border: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.border
    val Hairline: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.hairline
    val Text: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.text
    val Text2: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.text2
    val Text3: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.text3
    val Accent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.accent
    val AccentFill: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.accentFill
    val OnAccent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.onAccent
    val Up: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.up
    val UpBg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.upBg
    val Down: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.down
    val DownBg: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.downBg
    val BarIdle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalZColors.current.barIdle
}

/** 把 ZType 铺进 Material 的排版槽位，避免 TextField 标签这类组件回落到 M3 默认的 0.5sp 字距 */
private fun zTypography() = Typography(
    displayLarge = ZType.display,
    displayMedium = ZType.display,
    displaySmall = ZType.displaySmall,
    headlineLarge = ZType.display,
    headlineMedium = ZType.displaySmall,
    headlineSmall = ZType.title,
    titleLarge = ZType.title,
    titleMedium = ZType.titleSmall,
    titleSmall = ZType.bodySmall,
    bodyLarge = ZType.body,
    bodyMedium = ZType.bodySmall,
    bodySmall = ZType.secondary,
    labelLarge = ZType.titleSmall,
    labelMedium = ZType.secondary,
    labelSmall = ZType.caption,
)

@Composable
fun ZhouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val c = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accentFill,
            onPrimary = c.onAccent,
            secondary = c.accent,
            onSecondary = c.surface,
            background = c.bg,
            onBackground = c.text,
            surface = c.surface,
            onSurface = c.text,
            surfaceVariant = c.sunk,
            onSurfaceVariant = c.text2,
            outline = c.border,
            outlineVariant = c.hairline,
            error = c.up,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = c.accentFill,
            onPrimary = c.onAccent,
            secondary = c.accent,
            onSecondary = c.surface,
            background = c.bg,
            onBackground = c.text,
            surface = c.surface,
            onSurface = c.text,
            surfaceVariant = c.sunk,
            onSurfaceVariant = c.text2,
            outline = c.border,
            outlineVariant = c.hairline,
            error = c.up,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalZColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = zTypography(), content = content)
    }
}
