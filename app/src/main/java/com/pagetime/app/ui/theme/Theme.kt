package com.pagetime.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = OnAccentSoft,
    secondary = Ink500,
    onSecondary = Color.White,
    secondaryContainer = PaperMuted,
    onSecondaryContainer = Ink900,
    background = Paper,
    onBackground = Ink900,
    surface = PaperRaised,
    onSurface = Ink900,
    surfaceVariant = PaperMuted,
    onSurfaceVariant = Ink500,
    outline = Ink300,
    outlineVariant = Hairline,
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = NightAccent,
    onPrimary = Color(0xFF04201D),
    primaryContainer = NightAccentSoft,
    onPrimaryContainer = NightAccent,
    secondary = Color(0xFFA6B0BC),
    onSecondary = Color(0xFF1A2027),
    secondaryContainer = NightMuted,
    onSecondaryContainer = Color(0xFFD2D9E1),
    background = Night,
    onBackground = Color(0xFFE7EAEE),
    surface = NightRaised,
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = NightMuted,
    onSurfaceVariant = Color(0xFFA6B0BC),
    outline = NightHairline,
    outlineVariant = NightHairline,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

private val Shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp)
)

@Composable
fun PageTimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}