package com.pagetime.app.ui.screens.settings

import com.pagetime.app.data.learning.CardDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {

    private val saved = SettingsDraft(
        lockOn = true,
        targetSeconds = 20 * 60,
        otherAppsCount = false,
        otherAppsCapSeconds = 3_600,
        readingRate = 1.5,
        reviewReminders = true,
        cardDensity = CardDensity.NORMAL,
        suggestCards = true,
        suggestEveryWords = 1_500,
        slipBoxHelp = true,
    )

    @Test
    fun `an untouched page has nothing to save`() {
        assertTrue(SettingsDraft.changes(saved, saved.copy()).isEmpty())
    }

    @Test
    fun `changing a value and changing it back is nothing to save`() {
        val there = saved.copy(targetSeconds = 30 * 60)
        assertEquals(1, SettingsDraft.changes(saved, there).size)
        assertTrue(SettingsDraft.changes(saved, there.copy(targetSeconds = 20 * 60)).isEmpty())
    }

    @Test
    fun `each change reads from what it was to what it will be`() {
        val draft = saved.copy(
            targetSeconds = 30 * 60,
            cardDensity = CardDensity.FEW,
            suggestEveryWords = 2_500,
            reviewReminders = false,
        )
        val lines = SettingsDraft.changes(saved, draft).map { "${it.setting}: ${it.from} → ${it.to}" }
        assertEquals(
            listOf(
                "Daily reading target: 20 min → 30 min",
                "Review reminders: On → Off",
                "How many cards: Balanced → Only the best",
                "Suggest cards: Every 1,500 words → Every 2,500 words",
            ),
            lines,
        )
    }

    @Test
    fun `switching the lock says what follows, in both directions`() {
        val off = SettingsDraft.changes(saved, saved.copy(lockOn = false)).single()
        assertEquals("On", off.from)
        assertEquals("Off", off.to)
        assertTrue(off.consequence!!.contains("24 hours"))

        val wasOff = saved.copy(lockOn = false)
        val on = SettingsDraft.changes(wasOff, wasOff.copy(lockOn = true, targetSeconds = 45 * 60))
        assertTrue(on.first().consequence!!.contains("45 min"))
    }

    @Test
    fun `an ordinary change carries no warning`() {
        assertNull(SettingsDraft.changes(saved, saved.copy(slipBoxHelp = false)).single().consequence)
    }

    @Test
    fun `times and rates are worded for a person`() {
        assertEquals("45 min", SettingsDraft.minutes(45 * 60))
        assertEquals("1 hr", SettingsDraft.minutes(3_600))
        assertEquals("1 hr 30 min", SettingsDraft.minutes(5_400))
        assertEquals("0 min", SettingsDraft.minutes(0))
        assertEquals("×1.5", SettingsDraft.rate(1.5))
    }
}
