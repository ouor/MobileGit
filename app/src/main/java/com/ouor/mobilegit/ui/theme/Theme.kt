package com.ouor.mobilegit.ui.theme

import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens. Rationale and usage rules: docs/ui/design-system.md

/** Spacing scale (4dp grid). Screens use [Space.screen] as the horizontal gutter. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
}

/** Text/buttons use a deeper blue than the icon (#3366FF) so links on the grey background reach 4.5:1 (RV2-10). */
private val Brand = Color(0xFF2B5AE8)
private val BrandDark = Color(0xFF7C9CFF)

private val LightColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    primaryContainer = Color(0xFFE5ECFF), onPrimaryContainer = Color(0xFF0B2A80),
    secondary = Color(0xFF4E5968), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EBEF), onSecondaryContainer = Color(0xFF191F28),
    tertiary = Color(0xFF00A86B), onTertiary = Color.White,
    background = Color(0xFFF2F4F6), onBackground = Color(0xFF191F28),
    surface = Color(0xFFF2F4F6), onSurface = Color(0xFF191F28),
    surfaceVariant = Color(0xFFE8EBEF), onSurfaceVariant = Color(0xFF5C6774), // RV2-10: 4.5:1 on background (was #6B7684, 4.2:1)
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE8EBEF),
    surfaceBright = Color.White,
    outline = Color(0xFFD1D6DB), outlineVariant = Color(0xFFE5E8EB),
    error = Color(0xFFE5303B), onError = Color.White,
    errorContainer = Color(0xFFFFE9EA), onErrorContainer = Color(0xFF8A0F17),
    inverseSurface = Color(0xFF191F28), inverseOnSurface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandDark, onPrimary = Color(0xFF00186B),
    primaryContainer = Color(0xFF1E2F66), onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFB0B8C1), onSecondary = Color(0xFF191F28),
    secondaryContainer = Color(0xFF2C2F36), onSecondaryContainer = Color(0xFFE5E8EB),
    tertiary = Color(0xFF3DD68C), onTertiary = Color(0xFF00391F),
    background = Color(0xFF111215), onBackground = Color(0xFFECEEF1),
    surface = Color(0xFF111215), onSurface = Color(0xFFECEEF1),
    surfaceVariant = Color(0xFF2C2F36), onSurfaceVariant = Color(0xFF9EA6B0),
    surfaceContainerLowest = Color(0xFF1B1D21), surfaceContainerLow = Color(0xFF1B1D21),
    surfaceContainer = Color(0xFF1B1D21), surfaceContainerHigh = Color(0xFF22252A), surfaceContainerHighest = Color(0xFF2C2F36),
    surfaceBright = Color(0xFF22252A),
    outline = Color(0xFF3A3E46), outlineVariant = Color(0xFF2C2F36),
    error = Color(0xFFFF6B70), onError = Color(0xFF5C0007),
    errorContainer = Color(0xFF3D1518), onErrorContainer = Color(0xFFFFDADB),
    inverseSurface = Color(0xFFECEEF1), inverseOnSurface = Color(0xFF191F28),
)

/** Semantic status tones. Every badge/banner picks exactly one. Text always accompanies colour (NFR 접근성). */
enum class Tone { Neutral, Info, Success, Warning, Danger }

@Immutable
data class ToneColors(val container: Color, val content: Color)

@Immutable
data class StatusPalette(val neutral: ToneColors, val info: ToneColors, val success: ToneColors, val warning: ToneColors, val danger: ToneColors) {
    fun of(t: Tone) = when (t) {
        Tone.Neutral -> neutral; Tone.Info -> info; Tone.Success -> success; Tone.Warning -> warning; Tone.Danger -> danger
    }
}

private val LightStatus = StatusPalette(
    neutral = ToneColors(Color(0xFFF2F4F6), Color(0xFF4E5968)),
    info = ToneColors(Color(0xFFE8F0FF), Color(0xFF1B52D9)),
    success = ToneColors(Color(0xFFE3F7EE), Color(0xFF007A4D)),
    warning = ToneColors(Color(0xFFFFF3DB), Color(0xFF9A5B00)),
    danger = ToneColors(Color(0xFFFFE9EA), Color(0xFFC81E28)),
)
private val DarkStatus = StatusPalette(
    neutral = ToneColors(Color(0xFF2C2F36), Color(0xFFB0B8C1)),
    info = ToneColors(Color(0xFF1C2A4D), Color(0xFF9DB6FF)),
    success = ToneColors(Color(0xFF123526), Color(0xFF5BE0A0)),
    warning = ToneColors(Color(0xFF3B2C10), Color(0xFFFFC65C)),
    danger = ToneColors(Color(0xFF3D1518), Color(0xFFFF8A8F)),
)

val LocalStatus = staticCompositionLocalOf { LightStatus }

val Mono = FontFamily.Monospace

/** RV2-07: break Korean lines between words (어절), never inside "6시간" or before "요.". Needs API 33; older phones keep character breaks. */
private val Words = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
private fun TextStyle.words() = copy(lineBreak = Words)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp).words(),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp).words(),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp).words(),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 26.sp).words(),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp).words(),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp).words(),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp).words(),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp).words(),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).words(),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp).words(),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp).words(),
    )
}

/** Monospace styles for addresses, URLs, commands and SHAs. */
object CodeText {
    val large = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp)
    val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MobileGitTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatus provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Shorthand: `tone(Tone.Danger).container`. */
@Composable
fun tone(t: Tone): ToneColors = LocalStatus.current.of(t)
