package com.pagetime.app.data.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder is shipped data, and shipped data rots quietly: a duplicated key
 * silently drops an entry from the shelf, and a blank author makes a search
 * that matches everything.
 */
class ReadingLadderTest {

    private val ladder = ReadingLadders.greatBooks

    @Test
    fun `keys are unique, because a slot is addressed by them`() {
        val dupes = ladder.groupingBy { it.key }.eachCount().filterValues { it > 1 }
        assertTrue("duplicate ladder keys: ${dupes.keys}", dupes.isEmpty())
    }

    @Test
    fun `no entry is missing a field`() {
        ladder.forEach {
            assertTrue("blank key", it.key.isNotBlank())
            assertTrue("blank title for ${it.key}", it.title.isNotBlank())
            assertTrue("blank author for ${it.key}", it.author.isNotBlank())
            assertTrue("blank note for ${it.key}", it.note.isNotBlank())
        }
    }

    @Test
    fun `no book appears twice under different keys`() {
        val dupes = ladder.groupingBy { "${it.title.lowercase()}|${it.author.lowercase()}" }
            .eachCount().filterValues { it > 1 }
        assertTrue("same book listed twice: ${dupes.keys}", dupes.isEmpty())
    }

    /**
     * The list IS the order, so entries of one stage must not be scattered
     * through another — a reader working down the shelf would be sent back
     * and forth across two thousand years.
     */
    @Test
    fun `stages run in contiguous blocks and in order`() {
        val seen = mutableListOf<LadderStage>()
        ladder.forEach { if (seen.lastOrNull() != it.stage) seen += it.stage }
        assertEquals("a stage is interrupted and resumed", seen.distinct(), seen)
        assertEquals(LadderStage.entries, seen)
    }

    @Test
    fun `every stage has something in it`() {
        LadderStage.entries.forEach { stage ->
            assertTrue("$stage is empty", ladder.any { it.stage == stage })
        }
    }

    /**
     * Ordering claims the notes make out loud. If someone reorders the list,
     * these are the promises that break first.
     */
    @Test
    fun `the run-ups come before the books that need them`() {
        fun at(key: String) = ladder.indexOfFirst { it.key == key }
            .also { assertTrue("missing $key", it >= 0) }

        assertTrue("Hume must precede Kant", at("hume") < at("kant"))
        assertTrue("Homer must precede Virgil", at("iliad") < at("aeneid"))
        assertTrue("Odyssey must precede the Aeneid", at("odyssey") < at("aeneid"))
        assertTrue("Apology must precede the Republic", at("apology") < at("republic"))
        assertTrue("Portrait of the Artist introduces Joyce", at("portrait-artist") < ladder.size)
        assertTrue("Hobbes must precede Locke's reply", at("leviathan") < at("locke-govt"))
    }

    @Test
    fun `a search query names both the book and its author`() {
        val entry = ladder.first { it.key == "moby-dick" }
        assertEquals("Moby-Dick Herman Melville", entry.searchQuery)
    }

    @Test
    fun `the ladder is long enough to be a path and short enough to finish`() {
        assertTrue("ladder is ${ladder.size} entries", ladder.size in 40..90)
    }
}
