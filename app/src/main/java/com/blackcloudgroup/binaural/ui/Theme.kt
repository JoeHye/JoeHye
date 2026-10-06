package com.blackcloudgroup.binaural.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Single place for the app's colours, so a later visual redesign only touches this file.

// Night: deep ink background with a calm cyan accent. Low brightness for use in a dark room.
private val NightColors = darkColorScheme(
    primary = Color(0xFF7DD3E8),
    onPrimary = Color(0xFF00343F),
    primaryContainer = Color(0xFF16404B),
    onPrimaryContainer = Color(0xFFB8EAF5),
    secondary = Color(0xFFA9B4D0),
    onSecondary = Color(0xFF1B2337),
    secondaryContainer = Color(0xFF2A3350),
    onSecondaryContainer = Color(0xFFD6DDF2),
    background = Color(0xFF0A0F1C),
    onBackground = Color(0xFFDCE2EE),
    surface = Color(0xFF0F1626),
    onSurface = Color(0xFFDCE2EE),
    surfaceVariant = Color(0xFF1A2236),
    onSurfaceVariant = Color(0xFFA3ADC2),
    surfaceContainer = Color(0xFF141C2E),
    surfaceContainerHigh = Color(0xFF1A2236),
    outline = Color(0xFF465068),
    outlineVariant = Color(0xFF2A3348),
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
    secondaryContainer = Color(0xFFDDE3F6),
    onSecondaryContainer = Color(0xFF0C1630),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF151B26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151B26),
    surfaceVariant = Color(0xFFE6EAF1),
    onSurfaceVariant = Color(0xFF4A5468),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE8ECF2),
    outline = Color(0xFF7A8499),
    outlineVariant = Color(0xFFC9D0DC)
)

@Composable
fun BinauralTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) NightColors else DayColors, content = content)
}
