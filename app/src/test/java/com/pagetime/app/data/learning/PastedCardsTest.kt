package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedCardsTest {

    private val card1 = """{"passageIndex":0,"type":"qa","prompt":"Why did the Continental System fail?","answer":"Smuggling undermined it.","explanation":"Merchants kept trading through neutral ports, so the blockade starved France more than Britain.","sourceQuote":"merchants kept trading through neutral ports"}"""
    private val card2 = """{"passageIndex":0,"type":"cloze","prompt":"The blockade was announced in {{c1::1806}}.","answer":"1806","explanation":"The Berlin Decree opened the economic war between the two powers.","sourceQuote":"The blockade was announced in 1806."}"""

    // --- The shapes a chat window actually produces ---

    @Test
    fun `a clean reply in a fenced block gives every card`() {
        val reply = "```json\n{\"prompts\":[$card1,$card2]}\n```"
        val result = PastedCards.parse(reply)
        assertEquals(2, result.prompts.size)
        assertTrue(result.problems.isEmpty())
        assertEquals("Why did the Continental System fail?", result.prompts[0].prompt)
        assertEquals("merchants kept trading through neutral ports", result.prompts[0].sourceQuote)
    }

    @Test
    fun `chatter before and after the code block is ignored`() {
        val reply = """
            Sure! Here are "your" flashcards — I hope they help.

            ```json
            {"prompts":[$card1]}
            ```

            Let me know if you want {more} cards or a different style.
        """.trimIndent()
        val result = PastedCards.parse(reply)
        assertEquals(1, result.prompts.size)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `a reply with no fence and no wrapper still works`() {
        val result = PastedCards.parse("[$card1,\n$card2]")
        assertEquals(2, result.prompts.size)
    }

    @Test
    fun `a reply cut off mid card keeps every complete card before the cut`() {
        val reply = "```json\n{\"prompts\":[$card1,$card2,{\"passageIndex\":0,\"type\":\"qa\",\"prompt\":\"What was th"
        val result = PastedCards.parse(reply)
        assertEquals(2, result.prompts.size)
    }

    @Test
    fun `an unclosed code fence is read to the end`() {
        val result = PastedCards.parse("```json\n[$card1]")
        assertEquals(1, result.prompts.size)
    }

    // --- Cards are judged one at a time ---

    @Test
    fun `one damaged card is reported and the rest still import`() {
        val damaged = """{"prompt":"Broken?" "answer":"missing comma"}"""
        val result = PastedCards.parse("{\"prompts\":[$card1,$damaged,$card2]}")
        assertEquals(2, result.prompts.size)
        assertEquals(1, result.problems.size)
        assertTrue(result.problems[0].contains("could not be read"))
    }

    @Test
    fun `braces and escaped quotes inside a value do not break the scan`() {
        val tricky = """{"prompt":"What does the notation {x} mean here?","answer":"A set containing x.","explanation":"Braces denote a set, so {x} is the set whose only member is x \"as written\".","sourceQuote":"the notation {x} denotes a set"}"""
        val result = PastedCards.parse("```json\n[$tricky,$card2]\n```")
        assertEquals(2, result.prompts.size)
        assertTrue(result.prompts[0].explanation.contains("\"as written\""))
    }

    @Test
    fun `cards stay in the order the reader saw them`() {
        val result = PastedCards.parse("[$card2,$card1]")
        assertEquals(listOf("cloze", "qa"), result.prompts.map { it.type })
    }

    // --- Tolerance for how a model names things ---

    @Test
    fun `common alternative field names and cases are understood`() {
        val reply = """[{"Question":"Who signed it?","Answer":"Napoleon","Why":"He ordered the decree personally, which tied the policy to his authority from the start.","Quote":"Napoleon signed the decree"}]"""
        val p = PastedCards.parse(reply).prompts.single()
        assertEquals("Who signed it?", p.prompt)
        assertEquals("Napoleon", p.answer)
        assertTrue(p.explanation.startsWith("He ordered"))
        assertEquals("Napoleon signed the decree", p.sourceQuote)
    }

    @Test
    fun `source_quote and other separators are understood`() {
        val reply = """[{"prompt":"Q?","answer":"A","explanation":"Because.","source_quote":"the quote"}]"""
        assertEquals("the quote", PastedCards.parse(reply).prompts.single().sourceQuote)
    }

    @Test
    fun `a cloze is recognised from its deletion even without a type`() {
        val reply = """[{"prompt":"The war began in {{c1::1803}}.","answer":"1803","explanation":"x","sourceQuote":"The war began in 1803."}]"""
        assertTrue(PastedCards.parse(reply).prompts.single().isCloze)
    }

    @Test
    fun `a missing type is a plain question`() {
        val reply = """[{"prompt":"Q?","answer":"A","explanation":"x","sourceQuote":"q"}]"""
        assertFalse(PastedCards.parse(reply).prompts.single().isCloze)
    }

    /** One passage is sent, so whatever the model wrote here, even a 1-based count, is moot. */
    @Test
    fun `the passage index is always zero`() {
        val reply = """[{"passageIndex":1,"prompt":"Q?","answer":"A","explanation":"x","sourceQuote":"q"}]"""
        assertEquals(0, PastedCards.parse(reply).prompts.single().passageIndex)
    }

    @Test
    fun `curly quotes inside values are kept as they are`() {
        val reply = "[{\"prompt\":\"What is “the system”?\",\"answer\":\"A policy.\",\"explanation\":\"x\",\"sourceQuote\":\"“the system”\"}]"
        assertEquals("What is “the system”?", PastedCards.parse(reply).prompts.single().prompt)
    }

    // --- Nothing usable ---

    @Test
    fun `plain text with no braces says the wrong thing was pasted`() {
        val result = PastedCards.parse("Sorry, I can't help with that.")
        assertFalse(result.sawJson)
        assertTrue(result.prompts.isEmpty())
    }

    @Test
    fun `empty clipboard gives nothing and does not crash`() {
        val result = PastedCards.parse("")
        assertTrue(result.prompts.isEmpty())
        assertFalse(result.sawJson)
    }

    @Test
    fun `structure with no cards in it is explained`() {
        val result = PastedCards.parse("""{"status":"ok","count":0}""")
        assertTrue(result.prompts.isEmpty())
        assertTrue(result.sawJson)
        assertEquals(1, result.problems.size)
    }

    @Test
    fun `windows line endings and a byte order mark are tolerated`() {
        val result = PastedCards.parse("﻿```json\r\n[$card1]\r\n```")
        assertEquals(1, result.prompts.size)
    }
}
