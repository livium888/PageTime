package com.pagetime.app.ui.screens.bookshelf

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * The case the shelves are built into.
 *
 * WHY THIS SCREEN IS ALLOWED TO BE DARK
 *
 * The rest of the app is warm paper and ink, and this is the one screen that
 * is a piece of furniture instead of a page. Everything else here is
 * typography; a shelf is an object, and a bookcase with a white backing reads
 * as a diagram of a bookcase. So the case is walnut in both themes — paler
 * under a lit room, deeper at night so it does not glare — and the app's own
 * accent is kept for the one thing on the shelf that means something.
 *
 * The wood tones deliberately sit in the same family as the planks that were
 * already drawn here, so nothing about this is a new colour scheme.
 */
data class ShelfCase(
    val backTop: Color,
    val backBottom: Color,
    /** The top rail, behind the app bar. */
    val rail: Color,
    val plankTop: Color,
    val plankFace: Color,
    val plankEdge: Color,
    /** The dark line under a plank, and the grain on the back. */
    val groove: Color,
    /** Cast by the shelf above, and where books meet the plank. */
    val contactShadow: Color,
    val brass: Color,
    val brassDark: Color,
    val lettering: Color,
    val letteringDim: Color,
    /** A bookmark: the only saturated thing in the case. */
    val ribbon: Color,
    val ghostFill: Color,
    val ghostStroke: Color,
)

/** A lit room: walnut with the lights on. */
private val Walnut = ShelfCase(
    backTop = Color(0xFF5C472F),
    backBottom = Color(0xFF3D2E1E),
    rail = Color(0xFF493727),
    plankTop = Color(0xFF8A6A4A),
    plankFace = Color(0xFF6E5238),
    plankEdge = Color(0xFF4A3626),
    groove = Color(0xFF241809),
    contactShadow = Color(0xFF140E07),
    brass = Color(0xFFCBA862),
    brassDark = Color(0xFF8B6D34),
    lettering = Color(0xFFF3E9D9),
    letteringDim = Color(0xFFC0B096),
    ribbon = Color(0xFF37B39F),
    ghostFill = Color(0x14F3E9D9),
    ghostStroke = Color(0x8CF3E9D9),
)

/** Night: the same case with the lamps off. */
private val WalnutDark = ShelfCase(
    backTop = Color(0xFF38291A),
    backBottom = Color(0xFF231A11),
    rail = Color(0xFF2C2115),
    plankTop = Color(0xFF5E472F),
    plankFace = Color(0xFF46341F),
    plankEdge = Color(0xFF2B1F12),
    groove = Color(0xFF120C06),
    contactShadow = Color(0xFF0A0604),
    brass = Color(0xFFA98A4A),
    brassDark = Color(0xFF6E5628),
    lettering = Color(0xFFE9DDC9),
    letteringDim = Color(0xFFA99B84),
    ribbon = Color(0xFF5FC9B4),
    ghostFill = Color(0x14E9DDC9),
    ghostStroke = Color(0x8CE9DDC9),
)

fun shelfCase(dark: Boolean): ShelfCase = if (dark) WalnutDark else Walnut

@Composable
fun rememberShelfCase(): ShelfCase {
    val dark = isSystemInDarkTheme()
    return remember(dark) { shelfCase(dark) }
}

/**
 * The marks on the shelf that carry meaning, kept out of the drawing code so
 * they can be checked. Everything here is a pure function of numbers the shelf
 * already has; none of it is stored.
 */
object ShelfDecor {

    /**
     * Close enough to the end to call it read.
     *
     * Deliberately the same number the shelf grouping uses, so a book the
     * ViewModel files under "Reading now" is exactly a book that shows a
     * bookmark — two halves of one rule rather than two rules that agree today.
     */
    const val FINISHED = 0.95f

    /** Below this a book has not really been started. */
    private const val STARTED = 0.005f

    /**
     * Shortest and longest a bookmark can be, as a fraction of the spine.
     *
     * Never nothing, so a book barely begun still shows that it is the one
     * being read, and never the whole spine, so it does not read as a stripe.
     */
    private const val MIN_RIBBON = 0.16f
    private const val MAX_RIBBON = 0.74f

    /**
     * Average width of one character along the spine, in dp.
     *
     * The title is drawn at 10sp and the author at 8sp. These are averages
     * rather than per-glyph metrics, so the fit is approximate — which is the
     * right way round, because being roughly right keeps the author off the
     * spines where it plainly would not fit, and the title ellipsises for the
     * rare title made of wide letters.
     */
    private const val TITLE_CHAR_DP = 5.6f
    private const val AUTHOR_CHAR_DP = 4.4f

    /** Between the author and the title, and clear at each end of the spine. */
    private const val GAP_DP = 8.0f
    private const val END_PAD_DP = 14.0f

    fun isInProgress(progress: Float): Boolean = progress > STARTED && progress < FINISHED

    /**
     * How far down the spine the bookmark hangs.
     *
     * How far in the reader is, mapped into the visible range. Zero for a book
     * that has not been started or has been finished — a finished book has no
     * bookmark in it, which is what the ribbon says without any words.
     */
    fun ribbonFraction(progress: Float): Float {
        if (!isInProgress(progress)) return 0f
        val through = (progress / FINISHED).coerceIn(0f, 1f)
        return MIN_RIBBON + through * (MAX_RIBBON - MIN_RIBBON)
    }

    /**
     * The author, as it can be written down the spine, or null if it cannot.
     *
     * A spine is a fixed length and the title has to fit on it. Rather than
     * squeezing both until neither can be read, this gives up the author
     * entirely — and before that, tries the surname alone, which is how the
     * bindings this is imitating do it.
     */
    fun spineAuthor(spineHeightDp: Int, title: String, author: String): String? {
        val full = author.trim()
        if (full.isEmpty()) return null
        if (fitsAlong(spineHeightDp, title, full)) return full

        val surname = full.split(' ').last()
        if (surname.length < full.length && fitsAlong(spineHeightDp, title, surname)) {
            return surname
        }
        return null
    }

    private fun fitsAlong(spineHeightDp: Int, title: String, author: String): Boolean {
        val needed =
            title.trim().length * TITLE_CHAR_DP +
                author.length * AUTHOR_CHAR_DP +
                GAP_DP +
                END_PAD_DP
        return spineHeightDp >= needed
    }
}
