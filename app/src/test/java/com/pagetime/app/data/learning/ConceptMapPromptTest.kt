package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptMapPromptTest {

    private val source = "Scarcity means wants exceed resources. Because of scarcity, every choice " +
        "has an opportunity cost, which is the value of the next best alternative given up."

    private fun concept(label: String, quote: String, confidence: Double = 0.9) =
        """{"label":"$label","description":"A description long enough.","type":"idea","sourceQuote":"$quote","confidence":$confidence}"""

    private fun relation(from: String, to: String, quote: String) =
        """{"sourceLabel":"$from","targetLabel":"$to","relationType":"causes","explanation":"One leads to the other.","sourceQuote":"$quote","confidence":0.9}"""

    private fun reply(concepts: List<String>, relations: List<String> = emptyList()) =
        """{"concepts":[${concepts.joinToString(",")}],"relationships":[${relations.joinToString(",")}]}"""

    private val scarcity = concept("Scarcity", "Scarcity means wants exceed resources.")
    private val cost = concept("Opportunity cost", "every choice has an opportunity cost")

    @Test
    fun `concepts and a relationship quoting the source are kept`() {
        val map = ConceptMapPrompt.parse(
            reply(listOf(scarcity, cost), listOf(relation("Scarcity", "Opportunity cost", "Because of scarcity, every choice"))),
            source,
        )!!
        assertEquals(listOf("Scarcity", "Opportunity cost"), map.concepts.map { it.label })
        assertEquals(1, map.relationships.size)
    }

    @Test
    fun `a reply in a code fence with chatter around it is read`() {
        val text = "Here is the map:\n```json\n${reply(listOf(scarcity))}\n```\nHope that helps."
        assertEquals(1, ConceptMapPrompt.parse(text, source)!!.concepts.size)
    }

    @Test
    fun `a concept whose quote is not in the source is dropped`() {
        val invented = concept("Inflation", "Inflation is a general rise in prices.")
        val map = ConceptMapPrompt.parse(reply(listOf(scarcity, invented)), source)!!
        assertEquals(listOf("Scarcity"), map.concepts.map { it.label })
    }

    @Test
    fun `a quote differing only in spacing and case still counts`() {
        val spaced = concept("Scarcity", "scarcity   means wants\\nexceed resources.")
        assertEquals(1, ConceptMapPrompt.parse(reply(listOf(spaced)), source)!!.concepts.size)
    }

    @Test
    fun `a low confidence concept is dropped`() {
        val unsure = concept("Scarcity", "Scarcity means wants exceed resources.", confidence = 0.3)
        assertTrue(ConceptMapPrompt.parse(reply(listOf(unsure)), source)!!.concepts.isEmpty())
    }

    @Test
    fun `the same label twice is kept once`() {
        assertEquals(1, ConceptMapPrompt.parse(reply(listOf(scarcity, scarcity)), source)!!.concepts.size)
    }

    @Test
    fun `a relationship to a concept that did not survive is dropped`() {
        val invented = concept("Inflation", "Inflation is a general rise in prices.")
        val map = ConceptMapPrompt.parse(
            reply(listOf(scarcity, invented), listOf(relation("Scarcity", "Inflation", "Because of scarcity, every choice"))),
            source,
        )!!
        assertTrue(map.relationships.isEmpty())
    }

    @Test
    fun `a relationship from a concept to itself is dropped`() {
        val map = ConceptMapPrompt.parse(
            reply(listOf(scarcity), listOf(relation("Scarcity", "scarcity", "Because of scarcity, every choice"))),
            source,
        )!!
        assertTrue(map.relationships.isEmpty())
    }

    @Test
    fun `an empty map is a valid answer`() {
        val map = ConceptMapPrompt.parse("""{"concepts":[],"relationships":[]}""", source)
        assertNotNull(map)
        assertTrue(map!!.concepts.isEmpty())
    }

    @Test
    fun `prose or unrelated JSON is not a map`() {
        assertNull(ConceptMapPrompt.parse("I could not find any concepts.", source))
        assertNull(ConceptMapPrompt.parse("""{"accuracy": 4}""", source))
    }

    @Test
    fun `the prompt carries the source and the concepts already known`() {
        val context = LearningContext(
            bookId = "b", bookTitle = "Economics", chapterIndex = 0, chapterTitle = "Chapter 1",
            recentText = source, sourceFormat = "epub",
        )
        val prompt = ConceptMapPrompt.build(context, listOf("Trade-offs"))
        assertTrue(prompt.contains(source))
        assertTrue(prompt.contains("Trade-offs"))
        assertTrue(prompt.contains("\"relationships\""))
    }
}
