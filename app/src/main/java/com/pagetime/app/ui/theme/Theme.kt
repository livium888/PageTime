package com.pagetime.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal600,
    onPrimary = Color.White,
    primaryContainer = Teal200,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Slate600,
    background = PaperLight,
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Teal600,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFCBD5E1),
    background = PaperDark,
    surface = SurfaceDark
)

@Composable
fun PageTimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
