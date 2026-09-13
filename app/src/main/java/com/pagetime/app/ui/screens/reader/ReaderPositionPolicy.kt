package com.pagetime.app.ui.screens.reader

import kotlin.math.roundToInt

/** Pure rules shared by the reader's EPUB and plain-text persistence paths. */
object ReaderPositionPolicy {
    fun canPersist(restoreComplete: Boolean): Boolean = restoreComplete

    fun clampFraction(value: Float): Float = value.coerceIn(0f, 1f)

    /**
     * How far down a list item the reader is, as a fraction of that item.
     *
     * A saved place is a pixel offset, and pixels do not survive rotation. A
     * PDF page is drawn full-bleed, so turning the phone makes every page
     * wider and, by the same factor, taller. Two thousand pixels into a page
     * is halfway down it in portrait and a quarter of the way down the same
     * page in landscape — and if the page grows by more than the offset, the
     * offset outlives the page it pointed into and the reader is thrown onto
     * the next one. A fraction of the item is the same line of text in either
     * orientation, which is why this is what gets remembered.
     */
    fun fractionOf(offset: Int, itemHeight: Int): Float =
        if (itemHeight <= 0) 0f else clampFraction(offset.toFloat() / itemHeight.toFloat())

    /**
     * The pixel offset that puts the reader back on the line the fraction
     * names, measured against the item's height in the orientation being
     * restored to rather than the one it was recorded in.
     */
    fun offsetFor(fraction: Float, itemHeight: Float): Int =
        (clampFraction(fraction) * itemHeight).roundToInt()
}
