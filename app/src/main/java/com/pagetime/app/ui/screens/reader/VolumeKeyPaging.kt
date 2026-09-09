package com.pagetime.app.ui.screens.reader

import android.view.KeyEvent

/** Which way a page turn goes. */
enum class PageTurn { FORWARD, BACK }

/**
 * Turning pages with the volume keys.
 *
 * WHY THIS AND NOT ANOTHER GESTURE
 *
 * The reading surface is already fully spoken for: a horizontal swipe turns
 * the page, a tap toggles the chrome, a vertical drag on the left edge sets
 * brightness, and a long press on an EPUB starts a selection. Any new gesture
 * would have to be carved out of one of those, and every carve-out is a way
 * for the reader to get something they did not mean.
 *
 * The volume keys are a different input channel entirely, so this cannot
 * collide with any of it. It is also what makes one-handed reading work — in
 * bed, on a train, with the phone propped against something.
 *
 * DOWN IS FORWARD
 *
 * Which matches every e-reader that offers this, and matches the intuition
 * that down is onward through a stack of pages. Wired the other way it feels
 * wrong immediately and nobody can quite say why.
 *
 * AUTO-REPEAT IS IGNORED, DELIBERATELY
 *
 * A held key repeats around twenty times a second. That is not "paging
 * quickly", it is losing your place — and it would trip the reading guard's
 * pace limiter, which watches for exactly that kind of movement and would
 * correctly conclude nobody could be reading. One press, one page.
 *
 * BOTH HALVES OF THE PRESS ARE SWALLOWED
 *
 * Android raises the volume UI on key-UP. Handling only the down stroke turns
 * the page and then slides the volume panel over the text, which looks like a
 * bug and is the single most likely way to get this wrong.
 */
object VolumeKeyPaging {

    /**
     * What this key press should do, or null to let Android have it.
     *
     * [readerVisible] is the whole of the safety story: outside the reader the
     * volume keys are volume keys, so nobody is ever stuck unable to turn the
     * sound down.
     */
    fun turnFor(
        keyCode: Int,
        enabled: Boolean,
        readerVisible: Boolean,
        repeatCount: Int,
    ): PageTurn? {
        if (!enabled || !readerVisible) return null
        if (repeatCount > 0) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> PageTurn.FORWARD
            KeyEvent.KEYCODE_VOLUME_UP -> PageTurn.BACK
            else -> null
        }
    }

    /**
     * Whether to consume the event without acting on it.
     *
     * True for the key-UP of a press that was handled, and for the repeats of a
     * held key. Both would otherwise reach Android and raise the volume panel
     * over the page — the down stroke having already been taken.
     */
    fun consumesWithoutTurning(
        keyCode: Int,
        enabled: Boolean,
        readerVisible: Boolean,
    ): Boolean =
        enabled &&
            readerVisible &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
}

/**
 * Where the reader publishes its page-turn handler for the activity to find.
 *
 * A key event arrives at the Activity, and the thing that knows how to turn a
 * page is a Composable several layers down — one of two, since a plain-text
 * page turn moves a pager and an EPUB page turn asks Readium. Rather than
 * thread a callback down through the navigation graph, the reader registers
 * itself while it is on screen and clears itself on the way out.
 *
 * Clearing is what makes [VolumeKeyPaging.turnFor]'s readerVisible argument
 * true only when it should be: nothing else can leave a stale handler behind,
 * because the only thing that sets one also removes it.
 */
object ReaderPageTurns {

    @Volatile
    private var handler: ((PageTurn) -> Unit)? = null

    @Volatile
    var enabled: Boolean = false
        private set

    val active: Boolean get() = handler != null

    fun register(enabled: Boolean, handler: (PageTurn) -> Unit) {
        this.handler = handler
        this.enabled = enabled
    }

    /** Called when the reader leaves the screen. Idempotent. */
    fun unregister() {
        handler = null
        enabled = false
    }

    /** Returns whether a handler took it. */
    fun turn(direction: PageTurn): Boolean {
        val h = handler ?: return false
        h(direction)
        return true
    }
}
