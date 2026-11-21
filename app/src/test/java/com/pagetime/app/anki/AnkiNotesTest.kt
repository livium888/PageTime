package com.pagetime.app.anki

import com.pagetime.app.data.local.LearningCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiNotesTest {

    private fun card(
        id: String = "c1", prompt: String = "What does acid do to pectin?", answer: String = "Keeps it firm",
        type: String = "qa", status: String = "kept", created: Long = 1_000L, options: String? = null,
        quote: String? = "Acid slows pectin breakdown", chapter: String? = "Acid",
    ) = LearningCardEntity(
        id = id, bookId = "b", chapterIndex = 0, chapterTitle = chapter, prompt = prompt, answer = answer,
        explanation = null, sourceLocator = null, sourceFraction = null, sourceQuote = quote,
        fsrsCardJson = "{}", createdAt = created, updatedAt = created, cardType = type,
        mcqOptions = options, status = status,
    )

    @Test
    fun `a question becomes front and back with its source, book and chapter`() {
        val f = AnkiNotes.fieldsFor(card(), "Vegetables & Acid")
        assertEquals(AnkiNotes.FIELDS.size, f.size)
        assertEquals("What does acid do to pectin?", f[0])
        assertEquals("Keeps it firm", f[1])
        assertEquals("Acid slows pectin breakdown", f[2])
        assertEquals("Vegetables &amp; Acid", f[3])
        assertEquals("Acid", f[4])
    }

    @Test
    fun `a cloze card is sent with the gap on the front and the whole sentence behind it`() {
        val f = AnkiNotes.fieldsFor(card(prompt = "Acid {{c1::keeps}} pectin firm.", type = "cloze", answer = ""), "B")
        assertTrue(f[0].contains("…") && !f[0].contains("keeps"))
        assertEquals("Acid keeps pectin firm.", f[1])
    }

    @Test
    fun `a multiple choice card lists its options and markup is escaped`() {
        val f = AnkiNotes.fieldsFor(card(prompt = "a < b?", type = "mcq", options = """["Yes","No & maybe"]"""), "B")
        assertTrue(f[0].startsWith("a &lt; b?<ol"))
        assertTrue(f[0].contains("<li>No &amp; maybe</li>"))
    }

    @Test
    fun `tags carry pagetime and the book without spaces`() {
        assertEquals(setOf("pagetime", "why_we_sleep"), AnkiNotes.tagsFor("Why We Sleep!"))
        assertEquals(setOf("pagetime"), AnkiNotes.tagsFor("???"))
    }

    @Test
    fun `only new kept cards are sent, once, and nothing before it is switched on`() {
        val cards = listOf(
            card("old", created = 500L), card("new", created = 2_000L), card("sent", created = 3_000L),
            card("pending", created = 4_000L, status = "pending"), card("skipped", created = 4_000L, status = "skipped"),
        )
        assertEquals(listOf("new"), AnkiNotes.toSend(cards, since = 1_000L, alreadySent = setOf("sent")).map { it.id })
        assertEquals(emptyList<LearningCardEntity>(), AnkiNotes.toSend(cards, since = 0L, alreadySent = emptySet()))
    }

    @Test
    fun `the sent record round-trips`() {
        assertEquals(setOf("a", "b"), AnkiNotes.decodeSent(AnkiNotes.encodeSent(listOf("a", "b"))))
        assertEquals(emptySet<String>(), AnkiNotes.decodeSent(null))
    }
}
