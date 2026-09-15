package com.dshgo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计语言对齐 DSH 自己的移动端界面：浅色、克制、蓝色强调（#4D6BFE）。
 * 首页原生、会话内是 DSH 网页，两侧观感必须连续，否则切换时会「跳品牌」。
 */
object DshColor {
    val Accent = Color(0xFF4D6BFE)
    val AccentSoft = Color(0xFFEBEFFF)
    val Running = Color(0xFF22C55E)
    val RunningSoft = Color(0xFFE7F8EE)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val DangerSoft = Color(0xFFFDECEC)

    val BgLight = Color(0xFFF4F6F9)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceMutedLight = Color(0xFFF7F8FA)
    val OutlineLight = Color(0xFFE6E9EF)
    val TextLight = Color(0xFF10141C)
    val TextDimLight = Color(0xFF5B6472)
    val TextFaintLight = Color(0xFF98A1B0)

    val BgDark = Color(0xFF0B0E14)
    val SurfaceDark = Color(0xFF141922)
    val SurfaceMutedDark = Color(0xFF1B212C)
    val OutlineDark = Color(0xFF262D3A)
    val TextDark = Color(0xFFE9EDF6)
    val TextDimDark = Color(0xFF9AA4B8)
    val TextFaintDark = Color(0xFF6B7488)
}

private val LightScheme = lightColorScheme(
    primary = DshColor.Accent,
    onPrimary = Color.White,
    primaryContainer = DshColor.AccentSoft,
    onPrimaryContainer = DshColor.Accent,
    background = DshColor.BgLight,
    onBackground = DshColor.TextLight,
    surface = DshColor.SurfaceLight,
    onSurface = DshColor.TextLight,
    surfaceVariant = DshColor.SurfaceMutedLight,
    onSurfaceVariant = DshColor.TextDimLight,
    outline = DshColor.OutlineLight,
    outlineVariant = DshColor.OutlineLight,
    error = DshColor.Danger,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7B93FF),
    onPrimary = Color(0xFF06122E),
    primaryContainer = Color(0xFF232C48),
    onPrimaryContainer = Color(0xFFC7D2FF),
    background = DshColor.BgDark,
    onBackground = DshColor.TextDark,
    surface = DshColor.SurfaceDark,
    onSurface = DshColor.TextDark,
    surfaceVariant = DshColor.SurfaceMutedDark,
    onSurfaceVariant = DshColor.TextDimDark,
    outline = DshColor.OutlineDark,
    outlineVariant = DshColor.OutlineDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0A0A),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        // 会话标题是列表里最重要的信息，压紧一点、加重一点
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.3.sp),
    )
}

@Composable
fun DshTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Android 15 (API 35) 强制 edge-to-edge：状态栏/导航栏背景色被忽略，
    // 图标颜色必须跟着应用主题走，否则浅色界面上会出现「白图标压白底」看不见。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/** 主题里没覆盖、但各处都要用的语义色。 */
object DshSemantic {
    val running: Color @Composable get() = DshColor.Running
    val runningSoft: Color @Composable get() = DshColor.RunningSoft
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
}
