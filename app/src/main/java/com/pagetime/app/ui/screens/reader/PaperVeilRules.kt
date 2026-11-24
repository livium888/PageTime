package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.local.ReaderSettings
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the warm, dim veil does to a page, and how far it may go.
 *
 * Pure, so the arithmetic that decides what the reader sees can be checked
 * without a phone.
 *
 * WARMTH MULTIPLIES, IT DOES NOT PAINT
 *
 * The veil used to lay a 33%-opaque orange rectangle over the page. That adds
 * orange light to everything, black included, so a dark theme turned brown
 * and its text turned a muddy tan. A colour-temperature filter, like Android's
 * own Night Light, instead scales the colour channels: white becomes cream and
 * black stays black. [tintChannels] is that scale, applied by the veil as a
 * multiply blend.
 *
 * DIMMING IS HELD ABOVE A READABLE CONTRAST
 *
 * Darkening text and page by the same factor squeezes the gap between them:
 * the Dark theme's 11:1 falls to 2.5:1 at 62% dim. The slider used to run to
 * 80% with nothing to say the page had stopped being readable. [maxDim] is the
 * darkest a theme can go before its text is below [MIN_CONTRAST], and both the
 * slider and the drawing stop there.
 *
 * Contrast is measured the way WCAG does (relative luminance, with a flare
 * term), for the theme's own text on its own page. It ignores the window
 * backlight, which scales page and text alike, and it is a guide, not a law:
 * WCAG assumes ordinary room light and reads less reliably near black, which
 * is why the floor is 3:1 and not the 4.5:1 it asks of body text.
 */
object PaperVeilRules {

    /** The least contrast the veil may leave between a theme's text and its page. */
    const val MIN_CONTRAST = 3.0

    /** Full warmth: about the colour of a page under a 2700K lamp (RGB 255, 190, 120). */
    private const val WARM_GREEN = 190 / 255.0
    private const val WARM_BLUE = 120 / 255.0

    /** The red, green and blue factors (0 to 1) the page is multiplied by at [warmth]. */
    fun tintChannels(warmth: Float): DoubleArray {
        val w = warmth.coerceIn(0f, 1f).toDouble()
        return doubleArrayOf(1.0, 1.0 - w * (1.0 - WARM_GREEN), 1.0 - w * (1.0 - WARM_BLUE))
    }

    /** [tintChannels] as an opaque ARGB colour, for the multiply blend. */
    fun tintArgb(warmth: Float): Int {
        val c = tintChannels(warmth)
        fun byte(v: Double) = (v * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(c[0]) shl 16) or (byte(c[1]) shl 8) or byte(c[2])
    }

    /**
     * Text against page after the veil, as a WCAG contrast ratio (1 to 21).
     * Colours are ARGB ints; alpha is ignored.
     */
    fun contrast(background: Int, text: Int, warmth: Float, dim: Float): Double {
        val a = luminance(background, warmth, dim)
        val b = luminance(text, warmth, dim)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /**
     * The most [dim] a theme can take at this [warmth] and stay at or above
     * [MIN_CONTRAST], never past [ReaderSettings.MAX_NIGHT_DIM]. Zero if the
     * theme is below it even undimmed.
     */
    fun maxDim(
        background: Int,
        text: Int,
        warmth: Float,
        ceiling: Float = ReaderSettings.MAX_NIGHT_DIM,
    ): Float {
        if (contrast(background, text, warmth, ceiling) >= MIN_CONTRAST) return ceiling
        if (contrast(background, text, warmth, 0f) < MIN_CONTRAST) return 0f
        // Contrast only falls as dimming rises, so bisect for where it crosses.
        var low = 0f
        var high = ceiling
        repeat(24) {
            val mid = (low + high) / 2f
            if (contrast(background, text, warmth, mid) >= MIN_CONTRAST) low = mid else high = mid
        }
        return low
    }

    /** [dim] held to what the theme allows, so a saved value from another theme cannot hide the text. */
    fun effectiveDim(dim: Float, background: Int, text: Int, warmth: Float): Float =
        dim.coerceIn(0f, maxDim(background, text, warmth))

    /** True when [dim] sits at the limit [maxDim] and the theme could not take more. */
    fun atLimit(dim: Float, maxDim: Float): Boolean =
        maxDim < ReaderSettings.MAX_NIGHT_DIM - 0.001f && dim >= maxDim - 0.005f

    private fun luminance(argb: Int, warmth: Float, dim: Float): Double {
        val tint = tintChannels(warmth)
        val keep = 1.0 - dim.coerceIn(0f, 1f)
        fun channel(shift: Int, factor: Double): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0 * factor * keep
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16, tint[0]) + 0.7152 * channel(8, tint[1]) + 0.0722 * channel(0, tint[2])
    }
}
