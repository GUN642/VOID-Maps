package app.voidmaps.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.voidmaps.R
import app.voidmaps.data.ThemeChoice
import app.voidmaps.map.MapPalette
import app.voidmaps.map.Palettes

@Immutable
data class VoidColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textMuted: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    /** Second accent (chips, highlights). */
    val accent2: Color,
    val warn: Color,
    val dot: Color,
    val isDark: Boolean,
)

/** The designs resolved (AUTO is mapped to VOID or HELL). */
enum class Design { VOID, HELL }

fun resolveDesign(choice: ThemeChoice, systemDark: Boolean): Design = when (choice) {
    ThemeChoice.VOID -> Design.VOID
    ThemeChoice.HELL -> Design.HELL
    ThemeChoice.AUTO -> if (systemDark) Design.VOID else Design.HELL
}

fun mapPalette(design: Design): MapPalette = when (design) {
    Design.VOID -> Palettes.VOID
    Design.HELL -> Palettes.HELL
}

fun colorsFor(design: Design): VoidColors = when (design) {
    Design.VOID -> VoidColors(
        background = Color(0xFF000000),
        surface = Color(0xFF121212),
        surfaceHigh = Color(0xFF222222),
        text = Color(0xFFFFFFFF),
        textMuted = Color(0xFFB0B0B0),
        divider = Color.White.copy(alpha = 0.16f),
        accent = Color(0xFFD71921),
        onAccent = Color.White,
        accent2 = Color(0xFFD71921),
        warn = Color(0xFFD71921),
        dot = Color.White.copy(alpha = 0.10f),
        isDark = true,
    )
    Design.HELL -> VoidColors(
        background = Color(0xFFF1F1EE),
        surface = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFE6E5E0),
        text = Color(0xFF111111),
        textMuted = Color(0xFF6B6B6B),
        divider = Color.Black.copy(alpha = 0.09f),
        accent = Color(0xFFD71921),
        onAccent = Color.White,
        accent2 = Color(0xFFD71921),
        warn = Color(0xFFD71921),
        dot = Color.Black.copy(alpha = 0.10f),
        isDark = false,
    )
}

@OptIn(ExperimentalTextApi::class)
val DotFont = FontFamily(
    Font(
        R.font.doto,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f)),
    ),
    Font(
        R.font.doto,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("ROND", 100f)),
    ),
)

val MonoFont = FontFamily(Font(R.font.space_mono, FontWeight.Normal))

val LocalVoidColors = staticCompositionLocalOf { colorsFor(Design.VOID) }
val LocalDotHeadings = staticCompositionLocalOf { true }
val LocalDesign = staticCompositionLocalOf { Design.VOID }

object VoidTheme {
    val colors: VoidColors @Composable get() = LocalVoidColors.current
    val dotHeadings: Boolean @Composable get() = LocalDotHeadings.current
    val design: Design @Composable get() = LocalDesign.current
}

@Composable
fun systemDark(): Boolean = isSystemInDarkTheme()

@Composable
fun VoidMapsTheme(design: Design, dotHeadings: Boolean, content: @Composable () -> Unit) {
    val colors = colorsFor(design)
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.textMuted,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceHigh, surfaceContainerHighest = colors.surfaceHigh,
            surfaceContainerLow = colors.surface, surfaceContainerLowest = colors.background,
            outline = colors.textMuted, outlineVariant = colors.divider, secondary = colors.text, onSecondary = colors.background,
            error = Color(0xFFFF453A),
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.textMuted,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceHigh, surfaceContainerHighest = colors.surfaceHigh,
            surfaceContainerLow = colors.surface, surfaceContainerLowest = colors.background,
            outline = colors.textMuted, outlineVariant = colors.divider, secondary = colors.text, onSecondary = colors.background,
            error = Color(0xFFD70015),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !colors.isDark
            controller.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = if (dotHeadings) DotFont else FontFamily.Default),
        headlineMedium = base.headlineMedium.copy(fontFamily = if (dotHeadings) DotFont else FontFamily.Default),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium,
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        labelSmall = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, letterSpacing = 0.6.sp),
        labelMedium = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, letterSpacing = 0.8.sp),
        labelLarge = base.labelLarge.copy(fontFamily = MonoFont, letterSpacing = 1.sp),
    )

    CompositionLocalProvider(
        LocalVoidColors provides colors,
        LocalDotHeadings provides dotHeadings,
        LocalDesign provides design,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Heading style: dot-matrix when enabled, otherwise a clean grotesk. */
@Composable
fun headingStyle(size: Int): TextStyle = if (VoidTheme.dotHeadings) {
    TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = 1.sp)
} else {
    TextStyle(fontWeight = FontWeight.SemiBold, fontSize = (size * 0.85f).sp, letterSpacing = 0.sp)
}

/** Numbers in the navigation UI (distance, ETA) – always dot-matrix, that's the signature look. */
@Composable
fun dotNumber(size: Int): TextStyle =
    TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = 0.5.sp)
