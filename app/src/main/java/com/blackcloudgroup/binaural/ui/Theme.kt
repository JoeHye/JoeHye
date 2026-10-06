package com.blackcloudgroup.binaural.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.blackcloudgroup.binaural.R

// Single place for the app's look. Matches the "calm night instrument" mockup:
// deep ink ground, one cyan accent, Sora for headings and numbers, Figtree for text.

// Night: low brightness for a dark room.
private val NightColors = darkColorScheme(
    primary = Color(0xFF7DD3E8),
    onPrimary = Color(0xFF062A33),
    primaryContainer = Color(0xFF16404B),
    onPrimaryContainer = Color(0xFFB8EAF5),
    secondary = Color(0xFFA9B4D0),
    onSecondary = Color(0xFF1B2337),
    background = Color(0xFF0A0F1C),
    onBackground = Color(0xFFE4E8F1),
    surface = Color(0xFF121A2D),          // bottom panel, sections
    onSurface = Color(0xFFE4E8F1),
    surfaceVariant = Color(0xFF1E2840),   // progress-ring track
    onSurfaceVariant = Color(0xFF9AA4BA), // secondary text (7.9:1 on background)
    surfaceContainer = Color(0xFF121A2D),
    surfaceContainerHigh = Color(0xFF1B2439), // preset cards
    surfaceContainerHighest = Color(0xFF222C44),
    outline = Color(0xFF465068),
    outlineVariant = Color(0xFF2A3450),   // hairlines, outlined buttons
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF0F6A80),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEF6),
    onPrimaryContainer = Color(0xFF002A33),
    secondary = Color(0xFF4F5B78),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF141A26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141A26),
    surfaceVariant = Color(0xFFD3DAE6),
    onSurfaceVariant = Color(0xFF566177),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EEF5),
    surfaceContainerHighest = Color(0xFFE1E7F0),
    outline = Color(0xFF7A8499),
    outlineVariant = Color(0xFFD3DAE6)
)

// Variable fonts bundled in res/font (SIL OFL; licences in assets/licenses).
private fun sora(weight: Int) = Font(
    R.font.sora, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

private fun figtree(weight: Int) = Font(
    R.font.figtree, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val SoraFamily = FontFamily(sora(400), sora(600), sora(700))
val FigtreeFamily = FontFamily(figtree(400), figtree(500), figtree(600))

private val Base = Typography()

private val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    displayMedium = Base.displayMedium.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    displaySmall = Base.displaySmall.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = FigtreeFamily),
    bodyMedium = Base.bodyMedium.copy(fontFamily = FigtreeFamily),
    bodySmall = Base.bodySmall.copy(fontFamily = FigtreeFamily),
    labelLarge = Base.labelLarge.copy(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium)
)

@Composable
fun BinauralTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        typography = AppTypography,
        content = content
    )
}
