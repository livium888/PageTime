package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCardPromptTest {

    private val span = "Napoleon signed the Berlin Decree in 1806. " + "Merchants kept trading through neutral ports. ".repeat(10)

    private fun build(template: String? = null) =
        ManualCardPrompt.build("A History of Trade", "Chapter 4: The Blockade", span, template)

    @Test
    fun `the span is sent as the one numbered passage`() {
        val prompt = build()
        assertTrue(prompt.contains("[0]\n" + span.trim()))
    }

    @Test
    fun `the book and chapter are named and no placeholder is left behind`() {
        val prompt = build()
        assertTrue(prompt.contains("A History of Trade"))
        assertTrue(prompt.contains("Chapter 4: The Blockade"))
        assertFalse(prompt.contains("{{passages}}"))
        assertFalse(prompt.contains("{{book}}"))
        assertFalse(prompt.contains("{{chapter}}"))
        assertFalse(prompt.contains("{{howmany}}"))
    }

    @Test
    fun `the reply format comes last, because the last instruction is the one followed`() {
        val prompt = build()
        assertTrue(prompt.trimEnd().endsWith("A card whose quote is not in the passage is discarded."))
        assertTrue(prompt.indexOf("HOW TO REPLY") > prompt.indexOf("[0]"))
        assertTrue(prompt.contains("```json"))
        assertTrue(prompt.contains("{{c1::the hidden words}}"))
    }

    @Test
    fun `the shipped verbatim quote rule travels with it`() {
        assertTrue(build().contains("CHARACTER FOR CHARACTER"))
    }

    @Test
    fun `a readers own template is used`() {
        val prompt = build(template = "Be terse. {{book}} / {{chapter}}\n{{passages}}")
        assertTrue(prompt.startsWith("Be terse. A History of Trade / Chapter 4: The Blockade"))
        assertTrue(prompt.contains("HOW TO REPLY"))
    }

    @Test
    fun `how many cards to ask for scales with the length and stays within bounds`() {
        assertEquals(3, ManualCardPrompt.cardTarget(0))
        assertEquals(3, ManualCardPrompt.cardTarget(300))
        assertEquals(10, ManualCardPrompt.cardTarget(1500))
        assertEquals(20, ManualCardPrompt.cardTarget(10_000))
    }

    @Test
    fun `the requested ceiling appears in the instructions`() {
        val long = "word ".repeat(1500)
        val prompt = ManualCardPrompt.build("B", "C", long)
        assertTrue(prompt.contains("up to 10"))
    }

    /** The reply format must itself be something the parser accepts. */
    @Test
    fun `the example in the reply format parses`() {
        val example = ManualCardPrompt.REPLY_FORMAT
            .substringAfter("```json\n").substringBefore("\n```")
            .replace("\"...\"", "\"x\"")
        val parsed = PastedCards.parse(example)
        assertEquals(1, parsed.prompts.size)
        assertTrue(parsed.problems.isEmpty())
    }
}
