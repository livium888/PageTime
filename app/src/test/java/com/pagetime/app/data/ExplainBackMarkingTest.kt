package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainBackMarkingTest {

    private val good = """{"accuracy": 4, "completeness": 3, "clarity": 5,
        "whatTheyGotRight": "The cause.", "whatTheyMissed": "The effect.",
        "suggestedImprovement": "Add the effect.", "simplerVersion": "A causes B."}"""

    @Test
    fun `a plain JSON reply is read`() {
        val marks = ExplainBackMarking.parse(good)!!
        assertEquals(4, marks.accuracy)
        assertEquals(3, marks.completeness)
        assertEquals(5, marks.clarity)
        assertEquals(4.0f, marks.overallScore, 0.001f)
        assertEquals("The effect.", marks.whatTheyMissed)
        assertTrue(marks.hasBreakdown)
    }

    @Test
    fun `a reply wrapped in a code fence with chatter around it is read`() {
        val reply = "Sure! Here is the marking:\n```json\n$good\n```\nLet me know if you want more."
        assertNotNull(ExplainBackMarking.parse(reply))
    }

    @Test
    fun `braces inside a text value do not end the object early`() {
        val reply = """{"accuracy":5,"completeness":5,"clarity":5,
            "whatTheyGotRight":"Used {curly} braces and a \"quote\".","whatTheyMissed":"",
            "suggestedImprovement":"","simplerVersion":""}"""
        assertEquals("Used {curly} braces and a \"quote\".", ExplainBackMarking.parse(reply)!!.whatTheyGotRight)
    }

    @Test
    fun `scores outside the scale are held to it`() {
        val reply = """{"accuracy": 9, "completeness": 0, "clarity": 3.6}"""
        val marks = ExplainBackMarking.parse(reply)!!
        assertEquals(5, marks.accuracy)
        assertEquals(1, marks.completeness)
        assertEquals(4, marks.clarity)
    }

    @Test
    fun `a missing score is not invented`() {
        assertNull(ExplainBackMarking.parse("""{"accuracy": 4, "clarity": 4}"""))
    }

    @Test
    fun `prose with no JSON is not a marking`() {
        assertNull(ExplainBackMarking.parse("Your explanation was good, about 4 out of 5."))
        assertNull(ExplainBackMarking.parse(""))
    }

    @Test
    fun `an earlier non-JSON brace does not hide the real object`() {
        assertNotNull(ExplainBackMarking.parse("Note {see below}:\n$good"))
    }

    @Test
    fun `the prompt carries the concept, the source and the explanation`() {
        val prompt = ExplainBackMarking.prompt(
            conceptLabel = "Opportunity cost",
            keyPoints = listOf("the next best alternative"),
            source = "Every choice gives up the next best alternative.",
            userExplanation = "It is what you give up.",
            bookTitle = "Economics",
            chapterTitle = "Chapter 1",
        )
        assertTrue(prompt.contains("Opportunity cost"))
        assertTrue(prompt.contains("Every choice gives up the next best alternative."))
        assertTrue(prompt.contains("It is what you give up."))
        assertTrue(prompt.contains("\"accuracy\""))
    }

    @Test
    fun `a long source is cut to a window around the anchor`() {
        val anchor = "the anchor sentence is here"
        val source = "a".repeat(10_000) + anchor + "b".repeat(10_000)
        val window = ExplainBackMarking.sourceWindow(source, anchor)
        assertTrue(window.length <= ExplainBackMarking.MAX_SOURCE_CHARS)
        assertTrue(window.contains(anchor))
        assertFalse(ExplainBackMarking.sourceWindow("short", null).isEmpty())
    }
}
