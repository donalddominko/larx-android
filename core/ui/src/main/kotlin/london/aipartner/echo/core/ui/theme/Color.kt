package london.aipartner.echo.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fallback palette for pre-Android-12 / dynamic-color-off. Restrained, near-
// monochrome editorial base + one signature accent (Echo amber). Tuned in Phase 6.
private val Ink = Color(0xFF14110F)
private val Paper = Color(0xFFFBF9F6)
private val Accent = Color(0xFFE08A2C)
private val AccentDark = Color(0xFFF2A748)
private val Muted = Color(0xFF6B645C)

val EchoLightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    onSurfaceVariant = Muted,
)

val EchoDarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    onSurfaceVariant = Color(0xFFB7AEA4),
)
