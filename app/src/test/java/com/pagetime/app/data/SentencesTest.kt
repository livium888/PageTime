package com.pagetime.app.data

import com.pagetime.app.data.Sentences.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sentence kernel decides which words a highlight holds, so it is pinned
 * case by case here rather than trusted. Every case is a real shape a book or a
 * converted PDF produces: a numbered list, a date, an initial, a quotation, a
 * transcript with no punctuation at all.
 */
class SentencesTest {

    private fun at(text: String, offset: Int): String? =
        Sentences.spanAt(text, offset)?.let { text.substring(it.start, it.end) }

    private fun step(text: String, span: Span?, forward: Boolean): String? {
        val current = span ?: return null
        val next = if (forward) Sentences.next(text, current) else Sentences.previous(text, current)
        return next?.let { text.substring(it.start, it.end) }
    }

    // ── The ordinary case ──────────────────────────────────────────────────

    @Test
    fun `finds the sentence a point sits in`() {
        val text = "The cat sat. The dog ran."
        assertEquals("The cat sat.", at(text, 0))
        assertEquals("The cat sat.", at(text, 5))
        assertEquals("The cat sat.", at(text, 11))
        assertEquals("The dog ran.", at(text, 13))
        assertEquals("The dog ran.", at(text, text.lastIndex))
    }

    @Test
    fun `a point in the gap belongs to the sentence it follows`() {
        val text = "The cat sat. The dog ran."
        // Index 12 is the space between the two sentences: the reader pressing
        // there has just read the first one.
        assertEquals("The cat sat.", at(text, 12))
    }

    @Test
    fun `spans are trimmed of surrounding whitespace`() {
        val text = "  First one.   Second one.  "
        val first = Sentences.spanAt(text, 4)
        assertEquals("First one.", first?.let { text.substring(it.start, it.end) })
        assertEquals(2, first?.start)
    }

    @Test
    fun `blank text has no sentence`() {
        assertNull(Sentences.spanAt("", 0))
        assertNull(Sentences.spanAt("   \n\n  ", 3))
    }

    @Test
    fun `offsets outside the text are clamped rather than thrown`() {
        val text = "Only one here."
        assertEquals(text, at(text, -50))
        assertEquals(text, at(text, 9_999))
    }

    // ── Stepping ───────────────────────────────────────────────────────────

    @Test
    fun `steps forward and back one sentence at a time`() {
        val text = "One. Two. Three."
        val first = Sentences.spanAt(text, 0)
        assertEquals("One.", first?.let { text.substring(it.start, it.end) })

        val second = Sentences.next(text, first!!)
        assertEquals("Two.", second?.let { text.substring(it.start, it.end) })

        val third = Sentences.next(text, second!!)
        assertEquals("Three.", third?.let { text.substring(it.start, it.end) })

        assertNull(Sentences.next(text, third!!))
        assertEquals("Two.", step(text, third, forward = false))
    }

    @Test
    fun `stepping back from the first sentence stops`() {
        val text = "One. Two."
        assertNull(Sentences.previous(text, Sentences.spanAt(text, 0)!!))
    }

    @Test
    fun `stepping outwards from an extended span does not re-select what is held`() {
        val text = "One. Two. Three. Four."
        val first = Sentences.spanAt(text, 0)!!
        val second = Sentences.next(text, first)!!
        val held = first.through(second)
        assertEquals("One. Two.", text.substring(held.start, held.end))

        val third = Sentences.next(text, held)!!
        assertEquals("Three.", text.substring(third.start, third.end))
        // Nothing precedes the first sentence, and stepping back from a span
        // must not hand back the half of it that is already held.
        assertNull(Sentences.previous(text, held))
    }

    @Test
    fun `a union keeps the whitespace between the two sentences`() {
        val text = "One. Two."
        val held = Sentences.spanAt(text, 0)!!.through(Sentences.spanAt(text, 5)!!)
        assertEquals("One. Two.", text.substring(held.start, held.end))
    }

    // ── Abbreviations and numbers ──────────────────────────────────────────

    @Test
    fun `a title before a name does not end a sentence`() {
        val text = "Dr. Smith left. He was late."
        assertEquals("Dr. Smith left.", at(text, 0))
        assertEquals("He was late.", at(text, 16))
    }

    @Test
    fun `initials do not end a sentence`() {
        val text = "J. R. R. Tolkien wrote it. Then he stopped."
        assertEquals("J. R. R. Tolkien wrote it.", at(text, 0))
        assertEquals("Then he stopped.", at(text, 27))
    }

    @Test
    fun `a decimal point does not end a sentence`() {
        val text = "It costs 3.5 dollars. Yes."
        assertEquals("It costs 3.5 dollars.", at(text, 9))
        assertEquals("Yes.", at(text, 22))
    }

    @Test
    fun `a year at the end of a sentence still ends it`() {
        val text = "He arrived in 1999. He left soon after."
        assertEquals("He arrived in 1999.", at(text, 16))
        assertEquals("He left soon after.", at(text, 20))
    }

    @Test
    fun `a figure reference does not end a sentence`() {
        val text = "See Fig. 3 for the parts. It matters."
        assertEquals("See Fig. 3 for the parts.", at(text, 6))
    }

    @Test
    fun `a lowercase word that looks like an abbreviation still ends its sentence`() {
        // "no" is a reference abbreviation (No. 5) and an ordinary word. The
        // word wins unless a number follows.
        val text = "He said no. Then he left."
        assertEquals("He said no.", at(text, 4))
        assertEquals("Then he left.", at(text, 12))
    }

    @Test
    fun `a month abbreviation ends its sentence unless a date follows`() {
        val prose = "It was hot in Aug. Then it rained."
        assertEquals("It was hot in Aug.", at(prose, 4))

        val date = "It ended Aug. 5 that year."
        assertEquals(date, at(date, 4))
    }

    @Test
    fun `a dotted abbreviation does not end a sentence`() {
        val text = "Books, papers, e.g. this one, are here. Yes."
        assertEquals("Books, papers, e.g. this one, are here.", at(text, 3))
    }

    @Test
    fun `etc does not end a sentence`() {
        val text = "Books, papers, etc. are here. Yes."
        assertEquals("Books, papers, etc. are here.", at(text, 3))
    }

    // ── Punctuation shapes ─────────────────────────────────────────────────

    @Test
    fun `repeated terminators are one ending`() {
        val text = "What?! No way."
        assertEquals("What?!", at(text, 0))
        assertEquals("No way.", at(text, 8))
    }

    @Test
    fun `an ellipsis ends a sentence`() {
        val text = "Well... maybe later. Fine."
        assertEquals("Well...", at(text, 4))
        assertEquals("maybe later.", at(text, 12))
        assertEquals("Fine.", at(text, 21))
    }

    @Test
    fun `a closing quote after the stop belongs to the sentence`() {
        val text = "He said \u201Cstop.\u201D Then he left."
        assertEquals("He said \u201Cstop.\u201D", at(text, 4))
        assertEquals("Then he left.", at(text, 18))
    }

    @Test
    fun `a closing bracket after the stop belongs to the sentence`() {
        val text = "It ended (finally). The next one began."
        assertEquals("It ended (finally).", at(text, 3))
    }

    @Test
    fun `CJK terminators end a sentence`() {
        val text = "\u4ED6\u8D70\u4E86\u3002\u5979\u6765\u4E86\u3002"
        assertEquals("\u4ED6\u8D70\u4E86\u3002", at(text, 0))
        assertEquals("\u5979\u6765\u4E86\u3002", at(text, 4))
    }

    @Test
    fun `a trailing sentence with no stop is still a sentence`() {
        val text = "First one. A tail with no stop"
        assertEquals("A tail with no stop", at(text, 15))
    }

    // ── Paragraphs and the punctuation-free fallback ───────────────────────

    @Test
    fun `a sentence never spans a paragraph break`() {
        val text = "First paragraph sentence.\n\nSecond paragraph sentence."
        assertEquals("First paragraph sentence.", at(text, 0))
        assertEquals("Second paragraph sentence.", at(text, 30))
        // A sentence stops at the break, but the arrows may step across one: a
        // reader reaching the end of a paragraph and pulling more text into a
        // highlight means the next paragraph.
        assertEquals("Second paragraph sentence.", step(text, Sentences.spanAt(text, 0), forward = true))
    }

    @Test
    fun `a blank line with trailing spaces still separates paragraphs`() {
        val text = "One here.\n   \nTwo there."
        assertEquals("One here.", at(text, 2))
        assertEquals("Two there.", at(text, 14))
    }

    @Test
    fun `a paragraph with no punctuation falls back to its lines`() {
        val text = "Milk\nEggs\nBread"
        assertEquals("Milk", at(text, 0))
        assertEquals("Milk", at(text, 1))
        assertEquals("Eggs", at(text, 6))
        assertEquals("Bread", at(text, 11))
    }

    @Test
    fun `a numbered list is selectable item by item`() {
        val text = "1. First item\n2. Second item"
        assertEquals("1. First item", at(text, 0))
        assertEquals("2. Second item", at(text, 16))
    }

    @Test
    fun `a single newline inside prose does not end a sentence`() {
        // PDF extraction wraps lines mid-sentence; a wrapped sentence is one
        // sentence, not two.
        val text = "The blockade was tightened\nuntil the continent groaned."
        assertEquals("The blockade was tightened\nuntil the continent groaned.", at(text, 4))
    }

    @Test
    fun `paragraphAt returns the whole paragraph`() {
        val text = "One. Two.\n\nThree. Four."
        val paragraph = Sentences.paragraphAt(text, 0)!!
        assertEquals("One. Two.", text.substring(paragraph.start, paragraph.end))
        val second = Sentences.paragraphAt(text, 12)!!
        assertEquals("Three. Four.", text.substring(second.start, second.end))
    }

    @Test
    fun `long paragraphs are split rather than returned whole`() {
        val sentence = "This is a sentence about the blockade. "
        val text = sentence.repeat(40).trim()
        val found = Sentences.spanAt(text, 10)!!
        assertEquals(38, found.end - found.start)
        assertEquals("This is a sentence about the blockade.", text.substring(found.start, found.end))
    }
}
