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
}
