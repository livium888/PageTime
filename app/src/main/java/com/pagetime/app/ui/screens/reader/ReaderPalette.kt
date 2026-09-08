package com.pagetime.app.ui.screens.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.R

data class ReaderPalette(
    val key: String,
    val label: String,
    val background: Color,
    val text: Color,
    val secondary: Color,
    val bgHex: String,
    val textHex: String
)

/**
 * WHY "PAPER" IS NOT JUST A SLIGHTLY GREY WHITE
 *
 * Book paper reflects about 80% of the light falling on it and printing ink
 * about 8%, so the page you are used to reading has a contrast ratio around
 * 10:1. Pure black on pure white is 21:1, and on an OLED in a dark room it is
 * effectively unbounded. That gap is most of what makes a screen feel harsh
 * rather than restful, and it costs nothing to close.
 *
 * So Paper is a warm off-white with a soft near-black rather than #FFFFFF and
 * #000000, and lands near the ratio of a real book. Light is kept exactly as
 * it was for anyone who prefers the brighter page.
 */
val ReaderPalettes = listOf(
    ReaderPalette("paper", "Paper", Color(0xFFF4F1E8), Color(0xFF2E2A26), Color(0xFF7A736A), "#F4F1E8", "#2E2A26"),
    ReaderPalette("light", "Light", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF6B6B6B), "#FFFFFF", "#1A1A1A"),
    ReaderPalette("sepia", "Sepia", Color(0xFFF5EAD8), Color(0xFF4A3728), Color(0xFF8C7A6B), "#F5EAD8", "#4A3728"),
    ReaderPalette("dark", "Dark", Color(0xFF1E1E1E), Color(0xFFD5D5D5), Color(0xFF8A8A8A), "#1E1E1E", "#D5D5D5"),
    ReaderPalette("night", "Night", Color(0xFF000000), Color(0xFFB0B0B0), Color(0xFF555555), "#000000", "#B0B0B0")
)

fun paletteFor(themeKey: String): ReaderPalette =
    ReaderPalettes.firstOrNull { it.key == themeKey }
        // Falls back to Light rather than to the list's head, so adding a
        // palette at the front cannot silently change what an unrecognised
        // saved theme resolves to.
        ?: ReaderPalettes.first { it.key == "light" }

/**
 * The one text style the plain-text reader draws AND measures with.
 *
 * It exists because there were two of them. Pagination measures a page to
 * decide where it ends, and the reader then draws it with a style written out
 * separately a hundred lines away — so any difference between the two makes
 * pages that overflow or stop short, with nothing to say why. Adding
 * hyphenation to one and not the other would have done exactly that.
 *
 * HYPHENATION IS NOT A NICETY HERE
 *
 * Justified text without it is actively worse than ragged-right on a phone.
 * With nowhere to break a long word, the layout can only stretch the spaces
 * between words, and on a narrow column that opens rivers of white running
 * down the page. The app has offered justification since it was written and
 * has never hyphenated, so this repairs a setting rather than adding one.
 *
 * Android ships the hyphenation dictionaries; nothing needs to be bundled.
 */
fun readerTextStyle(base: TextStyle, settings: ReaderSettings): TextStyle {
    val justified = settings.alignment == "justify"
    return base.copy(
        fontFamily = readerFontFamily(settings.fontFamily),
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
        letterSpacing = (settings.fontSizeSp * 0.015f).sp,
        textAlign = if (justified) TextAlign.Justify else TextAlign.Start,
        // Only where it earns its keep. Ragged-right text has room to breathe
        // already, and breaking words in it just adds hyphens to read past.
        hyphens = if (justified) Hyphens.Auto else Hyphens.None,
        // Balances the whole paragraph rather than greedily filling each line
        // in turn, which is what stops one badly-stretched line at the end.
        lineBreak = LineBreak.Paragraph,
    )
}

fun fontStackFor(fontFamily: String): String = when (fontFamily) {
    "sans" -> "Roboto, 'Segoe UI', Helvetica, Arial, sans-serif"
    "mono" -> "'Courier New', Courier, monospace"
    else -> "Georgia, 'Times New Roman', 'Iowan Old Style', serif"
}

/** Font used by both the plain-text pages and the appearance sheet's live preview. */
fun readerFontFamily(key: String): FontFamily = when (key) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    "literata" -> FontFamily(Font(R.font.literata)) // Google's book-reading typeface, OFL-licensed
    else -> FontFamily.Serif
}
