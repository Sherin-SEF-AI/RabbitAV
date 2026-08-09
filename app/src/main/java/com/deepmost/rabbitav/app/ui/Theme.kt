package com.deepmost.rabbitav.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark-first driving palette (Section 2): black base, high-contrast accents. */
object RavColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF121820)
    val SurfaceHigh = Color(0xFF1B2430)
    val Amber = Color(0xFFFFB300)
    val AmberDim = Color(0xFF8A6200)
    val Red = Color(0xFFFF3B30)
    val Green = Color(0xFF34C759)
    val Blue = Color(0xFF4FA3FF)
    val TextPrimary = Color(0xFFF2F5F7)
    val TextSecondary = Color(0xFF9AA7B4)
    val CautionYellow = Color(0xFFFFD60A)
}

private val DarkScheme = darkColorScheme(
    primary = RavColors.Amber,
    onPrimary = Color.Black,
    secondary = RavColors.Blue,
    background = RavColors.Background,
    onBackground = RavColors.TextPrimary,
    surface = RavColors.Surface,
    onSurface = RavColors.TextPrimary,
    surfaceVariant = RavColors.SurfaceHigh,
    onSurfaceVariant = RavColors.TextSecondary,
    error = RavColors.Red,
)

@Composable
fun RabbitAvTheme(content: @Composable () -> Unit) {
    // Deliberately dark in both system themes: this is a night-driving HUD.
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
