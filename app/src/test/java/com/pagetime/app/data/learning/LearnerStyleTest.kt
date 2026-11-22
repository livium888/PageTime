package com.pagetime.app.data.learning

import com.pagetime.app.data.ExplainBackMarking
import com.pagetime.app.data.talk.TalkItThrough
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerStyleTest {

    @Test
    fun `the card prompt asks for understanding, and the reply format is still last`() {
        val prompt = ManualCardPrompt.build("A History of Trade", "Chapter 4", "A passage of some length about trade.")
        assertTrue(prompt.contains("STYLE OF THE QUESTIONS"))
        assertTrue(prompt.contains("Feynman"))
        assertTrue(prompt.contains("Franklin"))
        assertTrue(prompt.indexOf("STYLE OF THE QUESTIONS") < prompt.indexOf("HOW TO REPLY"))
        assertTrue(prompt.trimEnd().endsWith("A card whose quote is not in the passage is discarded."))
    }

    @Test
    fun `the Socratic prompt carries both ways of asking and still ends with the receipt`() {
        val p = TalkItThrough.buildPrompt("Book", "Chapter 1", "Text.", emptyList(), emptyList(), 2, "talk-abc12")
        assertTrue(p.contains("Feynman"))
        assertTrue(p.contains("Franklin"))
        assertTrue(p.contains("plain words"))
        assertTrue(p.contains("in my own words from memory"))
        assertTrue(p.trimEnd().contains("code: talk-abc12"))
        assertTrue(p.indexOf("Feynman") < p.indexOf("PAGETIME RECEIPT"))
    }

    @Test
    fun `the marking prompt looks for unexplained terms and still asks for the same JSON`() {
        val p = ExplainBackMarking.prompt("Adenosine", listOf("builds up"), "Source.", "My words.", "Book", "Ch 1")
        assertTrue(p.contains("technical term used but not explained"))
        assertTrue(p.contains("\"accuracy\""))
        assertTrue(p.contains("\"simplerVersion\""))
    }

    @Test
    fun `the roundtable seats the reader at a Junto, one speaker at a time, and still ends with the receipt`() {
        val p = TalkItThrough.buildPrompt(
            "Book", "Chapter 1", "Text.", emptyList(), emptyList(), 2, "talk-abc12",
            style = TalkItThrough.Style.ROUNDTABLE,
        )
        assertTrue(p.contains("Junto"))
        assertTrue(p.contains("Franklin chairs"))
        assertTrue(p.contains("I am a member of the club"))
        assertTrue(p.contains("Only ONE member speaks at a time"))
        assertTrue(p.contains("do not put quotations"))
        assertTrue(p.contains("Choose two other guests"))
        assertTrue(p.contains("Ask me 2 core questions, ONE at a time"))
        assertTrue(p.contains("code: talk-abc12"))
    }

    @Test
    fun `named guests are invited, and only people no longer living`() {
        val p = TalkItThrough.buildPrompt(
            "Book", "Chapter 1", "Text.", emptyList(), emptyList(), 1, "talk-abc12",
            style = TalkItThrough.Style.ROUNDTABLE, guests = "Marie Curie, David Hume",
        )
        assertTrue(p.contains("The guests are: Marie Curie, David Hume"))
        assertTrue(p.contains("no longer living"))
        assertTrue(!p.contains("Choose two other guests"))
    }

    @Test
    fun `the tutor style is unchanged`() {
        val p = TalkItThrough.buildPrompt("Book", "Chapter 1", "Text.", emptyList(), emptyList(), 1, "talk-abc12")
        assertTrue(p.startsWith("You are a Socratic tutor."))
        assertTrue(!p.contains("Junto"))
    }
}
