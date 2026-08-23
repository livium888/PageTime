package com.pagetime.app.ui.screens.reader

/** Keeps accidental taps separate from deliberate reading gestures. */
object ReaderInteractionPolicy {
    fun togglesChromeOnTap(): Boolean = true

    fun turnsPageOnEdgeTap(): Boolean = false

    fun turnsPageOnSwipe(): Boolean = true
}
