package com.pagetime.app.ui.screens.reader

import androidx.compose.ui.graphics.Color

data class ReaderPalette(
    val key: String,
    val label: String,
    val background: Color,
    val text: Color,
    val secondary: Color,
    val bgHex: String,
    val textHex: String
)

val ReaderPalettes = listOf(
    ReaderPalette("light", "Light", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF6B6B6B), "#FFFFFF", "#1A1A1A"),
    ReaderPalette("sepia", "Sepia", Color(0xFFF5EAD8), Color(0xFF4A3728), Color(0xFF8C7A6B), "#F5EAD8", "#4A3728"),
    ReaderPalette("dark", "Dark", Color(0xFF1E1E1E), Color(0xFFD5D5D5), Color(0xFF8A8A8A), "#1E1E1E", "#D5D5D5"),
    ReaderPalette("night", "Night", Color(0xFF000000), Color(0xFFB0B0B0), Color(0xFF555555), "#000000", "#B0B0B0")
)

fun paletteFor(themeKey: String): ReaderPalette =
    ReaderPalettes.firstOrNull { it.key == themeKey } ?: ReaderPalettes.first()

fun fontStackFor(fontFamily: String): String = when (fontFamily) {
    "sans" -> "Roboto, 'Segoe UI', Helvetica, Arial, sans-serif"
    "mono" -> "'Courier New', Courier, monospace"
    else -> "Georgia, 'Times New Roman', 'Iowan Old Style', serif"
}
