package app.zhouzhang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val Bg = Color(0xFFF6F4EF)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF1B2027)
    val Muted = Color(0xFF8A8F98)
    val Amber = Color(0xFFD9821B)
    val AmberSoft = Color(0xFFF2A93B)
    val Divider = Color(0xFFEAE6DD)
    val BarIdle = Color(0xFFD8D3C8)
}

private val Light = lightColorScheme(
    primary = Ink.Amber,
    onPrimary = Color.White,
    background = Ink.Bg,
    onBackground = Ink.Text,
    surface = Ink.Surface,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Bg,
    onSurfaceVariant = Ink.Muted,
    outline = Ink.Divider,
)

@Composable
fun ZhouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Light,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
            titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp),
        ),
        content = content,
    )
}
