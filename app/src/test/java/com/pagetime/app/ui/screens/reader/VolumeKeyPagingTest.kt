package com.pagetime.app.ui.screens.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyPagingTest {

    private fun turn(
        keyCode: Int,
        enabled: Boolean = true,
        readerVisible: Boolean = true,
        repeatCount: Int = 0,
    ) = VolumeKeyPaging.turnFor(keyCode, enabled, readerVisible, repeatCount)

    @Test
    fun `down goes forward and up goes back`() {
        assertEquals(PageTurn.FORWARD, turn(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(PageTurn.BACK, turn(KeyEvent.KEYCODE_VOLUME_UP))
    }

    /**
     * The safety story in one test: outside the reader the volume keys are
     * volume keys, so nobody is ever stuck unable to turn the sound down.
     */
    @Test
    fun `outside the reader the keys are left alone`() {
        assertNull(turn(KeyEvent.KEYCODE_VOLUME_DOWN, readerVisible = false))
        assertNull(turn(KeyEvent.KEYCODE_VOLUME_UP, readerVisible = false))
        assertFalse(
            VolumeKeyPaging.consumesWithoutTurning(
                KeyEvent.KEYCODE_VOLUME_DOWN, enabled = true, readerVisible = false,
            )
        )
    }

    @Test
    fun `switched off, the keys are left alone`() {
        assertNull(turn(KeyEvent.KEYCODE_VOLUME_DOWN, enabled = false))
        assertFalse(
            VolumeKeyPaging.consumesWithoutTurning(
                KeyEvent.KEYCODE_VOLUME_DOWN, enabled = false, readerVisible = true,
            )
        )
    }

    /**
     * A held key repeats around twenty times a second. That is not paging
     * quickly, it is losing your place — and it would trip the reading guard's
     * pace limiter, which watches for exactly that and would correctly
     * conclude nobody could be reading.
     */
    @Test
    fun `a held key turns one page and no more`() {
        assertEquals(PageTurn.FORWARD, turn(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0))
        assertNull(turn(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 1))
        assertNull(turn(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 47))
    }

    /**
     * Android raises the volume panel on key-UP. Handling only the down stroke
     * turns the page and then slides the volume UI over the text — the single
     * most likely way to get this wrong.
     */
    @Test
    fun `the rest of the press is swallowed so no volume panel appears`() {
        assertTrue(
            VolumeKeyPaging.consumesWithoutTurning(
                KeyEvent.KEYCODE_VOLUME_DOWN, enabled = true, readerVisible = true,
            )
        )
        assertTrue(
            VolumeKeyPaging.consumesWithoutTurning(
                KeyEvent.KEYCODE_VOLUME_UP, enabled = true, readerVisible = true,
            )
        )
    }

    @Test
    fun `other keys are never touched`() {
        listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_DPAD_DOWN,
        ).forEach { code ->
            assertNull("turned on $code", turn(code))
            assertFalse(
                "consumed $code",
                VolumeKeyPaging.consumesWithoutTurning(code, enabled = true, readerVisible = true),
            )
        }
    }

    // --- The handler registry ---

    @Test
    fun `a registered reader receives turns and a closed one does not`() {
        val seen = mutableListOf<PageTurn>()
        ReaderPageTurns.register(enabled = true) { seen += it }
        assertTrue(ReaderPageTurns.active)
        assertTrue(ReaderPageTurns.turn(PageTurn.FORWARD))
        assertEquals(listOf(PageTurn.FORWARD), seen)

        ReaderPageTurns.unregister()
        assertFalse(ReaderPageTurns.active)
        assertFalse("a closed reader still took a turn", ReaderPageTurns.turn(PageTurn.BACK))
        assertEquals(listOf(PageTurn.FORWARD), seen)
    }

    /**
     * Leaving the reader must also leave the setting off, or the activity would
     * keep swallowing volume keys for a screen that is no longer there.
     */
    @Test
    fun `closing the reader clears the enabled flag too`() {
        ReaderPageTurns.register(enabled = true) { }
        assertTrue(ReaderPageTurns.enabled)
        ReaderPageTurns.unregister()
        assertFalse(ReaderPageTurns.enabled)
    }

    @Test
    fun `unregistering twice is harmless`() {
        ReaderPageTurns.register(enabled = true) { }
        ReaderPageTurns.unregister()
        ReaderPageTurns.unregister()
        assertFalse(ReaderPageTurns.active)
    }
}
