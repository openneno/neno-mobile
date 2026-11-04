package com.openneno.neno.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.openneno.neno.data.Appearance
import com.openneno.neno.data.ThemeColor
import com.openneno.neno.data.ThemeMode

/** 主题色色板, 对应网页端 main.css 的 --primary-50 ... --primary-950 */
val PALETTES: Map<ThemeColor, List<Long>> = mapOf(
    ThemeColor.GREEN to listOf(0xFFF0FDF4, 0xFFDCFCE7, 0xFFB9F8CF, 0xFF7BF1A8, 0xFF05DF72, 0xFF00C950, 0xFF00A63E, 0xFF008236, 0xFF016630, 0xFF0D542B, 0xFF032E15),
    ThemeColor.TEAL to listOf(0xFFF0FDFA, 0xFFCBFBF1, 0xFF96F7E4, 0xFF46ECD5, 0xFF00D5BE, 0xFF00BBA7, 0xFF009689, 0xFF00786F, 0xFF005F5A, 0xFF0B4F4A, 0xFF022F2E),
    ThemeColor.BLUE to listOf(0xFFEFF6FF, 0xFFDBEAFE, 0xFFBEDBFF, 0xFF8EC5FF, 0xFF51A2FF, 0xFF2B7FFF, 0xFF155DFC, 0xFF1447E6, 0xFF193CB8, 0xFF1C398E, 0xFF162456),
    ThemeColor.VIOLET to listOf(0xFFF5F3FF, 0xFFEDE9FE, 0xFFDDD6FF, 0xFFC4B4FF, 0xFFA684FF, 0xFF8E51FF, 0xFF7F22FE, 0xFF7008E7, 0xFF5D0EC0, 0xFF4D179A, 0xFF2F0D68),
    ThemeColor.ROSE to listOf(0xFFFFF1F2, 0xFFFFE4E6, 0xFFFFCCD3, 0xFFFFA1AD, 0xFFFF637E, 0xFFFF2056, 0xFFEC003F, 0xFFC70036, 0xFFA50036, 0xFF8B0836, 0xFF4D0218),
    ThemeColor.ORANGE to listOf(0xFFFFF7ED, 0xFFFFEDD4, 0xFFFFD6A7, 0xFFFFB86A, 0xFFFF8904, 0xFFFF6900, 0xFFF54900, 0xFFCA3500, 0xFF9F2D00, 0xFF7E2A0C, 0xFF441306),
)

/** 主题色 index: 0=50 1=100 2=200 3=300 4=400 5=500 6=600 7=700 8=800 9=900 10=950 */
class Palette(colors: List<Long>) {
    private val c = colors.map { Color(it) }
    val p50 get() = c[0]
    val p100 get() = c[1]
    val p200 get() = c[2]
    val p300 get() = c[3]
    val p400 get() = c[4]
    val p500 get() = c[5]
    val p600 get() = c[6]
    val p700 get() = c[7]
    val p800 get() = c[8]
    val p900 get() = c[9]
    val p950 get() = c[10]
}

/** "安静的纸" 设计系统: 画布 < 卡片 < 次级面, 三级文字, 细线 */
@Immutable
data class NenoColors(
    val dark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val primary: Palette,
) {
    /** 主操作颜色 */
    val accent get() = if (dark) primary.p400 else primary.p600
    /** 标签, 链接等文字颜色 */
    val accentText get() = if (dark) primary.p300 else primary.p700
    val accentSoft get() = primary.p500.copy(alpha = if (dark) 0.16f else 0.10f)
    val danger get() = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
}

fun nenoColors(dark: Boolean, color: ThemeColor): NenoColors {
    val palette = Palette(PALETTES.getValue(color))
    return if (dark) NenoColors(
        true, Color(0xFF0F1013), Color(0xFF17191D), Color(0xFF202328), Color(0xFF2A2E34),
        Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.13f),
        Color(0xFFE7E8EA), Color(0xFFA8ADB5), Color(0xFF868C95), palette
    ) else NenoColors(
        false, Color(0xFFF6F6F3), Color(0xFFFFFFFF), Color(0xFFF0F0EC), Color(0xFFE7E7E2),
        Color(0xFF1C1917).copy(alpha = 0.08f), Color(0xFF1C1917).copy(alpha = 0.14f),
        Color(0xFF1D1F23), Color(0xFF565B63), Color(0xFF686D75), palette
    )
}

val LocalNenoColors = staticCompositionLocalOf { nenoColors(false, ThemeColor.GREEN) }

object Neno {
    val colors: NenoColors @Composable get() = LocalNenoColors.current
}

@Composable
fun isDark(appearance: Appearance): Boolean = when (appearance.mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun NenoTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDark(appearance)
    val c = nenoColors(dark, appearance.color)
    val scheme = if (dark) darkColorScheme(
        primary = c.primary.p400, onPrimary = Color(0xFF0F1013),
        primaryContainer = c.primary.p900, onPrimaryContainer = c.primary.p100,
        secondaryContainer = c.accentSoft, onSecondaryContainer = c.primary.p200,
        background = c.canvas, onBackground = c.ink, surface = c.canvas, onSurface = c.ink,
        surfaceVariant = c.surface2, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.canvas, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger,
    ) else lightColorScheme(
        primary = c.primary.p600, onPrimary = Color.White,
        primaryContainer = c.primary.p100, onPrimaryContainer = c.primary.p900,
        secondaryContainer = c.accentSoft, onSecondaryContainer = c.primary.p800,
        background = c.canvas, onBackground = c.ink, surface = c.canvas, onSurface = c.ink,
        surfaceVariant = c.surface2, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
        outline = c.lineStrong, outlineVariant = c.line, error = c.danger,
    )
    val base = Typography()
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalNenoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // 没有指定颜色的文字和图标使用正文颜色(否则在深色模式下是黑色)
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.ink, content = content)
        }
    }
}

val NoteTextStyle = TextStyle(fontSize = 15.sp, lineHeight = 25.sp)
