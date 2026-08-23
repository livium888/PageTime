package com.pagetime.app.ui.screens.reader

/** Pure rules shared by the reader's EPUB and plain-text persistence paths. */
object ReaderPositionPolicy {
    fun canPersist(restoreComplete: Boolean): Boolean = restoreComplete

    fun clampFraction(value: Float): Float = value.coerceIn(0f, 1f)
}
