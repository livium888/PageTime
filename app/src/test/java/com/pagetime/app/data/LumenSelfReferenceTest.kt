package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commonest bad card, and the one nothing caught.
 *
 * From a real capture log, Gemma 3 1B on a passage about religious
 * intermediaries returned:
 *
 *     "This passage reveals an adaptive tradition where individuals'
 *      reliance on indigenous spiritual experts was maintained by
 *      established institutions."
 *
 * It parses. It copies nothing, so the echo check passes it. Its back is two
 * sentences, so the back checks pass it. Trimmed to eight words it reaches the
 * reader as "This passage reveals an adaptive tradition where individuals'" —
 * a fragment pointing at a book they will not have in a year.
 *
 * The prompt forbade every part of that. A prohibition is not a check.
 */
class LumenSelfReferenceTest {

    @Test
    fun `the card from the log is caught`() {
        assertTrue(
            LumenCapture.mentionsSource(
                "This passage reveals an adaptive tradition where individuals' reliance " +
                    "on indigenous spiritual experts was maintained by established institutions."
            )
        )
    }

    @Test
    fun `pointing at the source in any of its usual disguises`() {
        listOf(
            "This passage reveals a tension",
            "The text argues for restraint",
            "The author claims otherwise",
            "That chapter undermines the case",
            "The book shows how trust forms",
            "These paragraphs describe a ritual",
            "The narrator is unreliable here",
            "The excerpt explains the mechanism",
        ).forEach { assertTrue("Not caught: $it", LumenCapture.mentionsSource(it)) }
    }

    /**
     * The other half of the job, and the harder one. A check that rejects real
     * claims costs a card every time it fires, so the bare nouns must pass —
     * only a source being POINTED AT is a report about a text.
     */
    @Test
    fun `real claims about books and authors survive`() {
        listOf(
            "Books outlive their authors",
            "Authors rarely control their reception",
            "Writing fixes a thought in place",
            "Readers finish a book the writer began",
            "The book trade created copyright",
            "The story of a life is told backwards",
            "A chapter ends where attention does",
            "Institutions outlast the people who staff them",
            "Trust binds strangers into strangers' bargains",
            "Fiction lets strangers cooperate",
            "Vetting transfers trust from person to office",
        ).forEach { assertFalse("Wrongly caught: $it", LumenCapture.mentionsSource(it)) }
    }

    @Test
    fun `a front that points at the source is a back problem when it is a back`() {
        assertEquals(
            LumenCapture.BackProblem.MENTIONS_SOURCE,
            LumenCapture.backProblem(
                front = "Vetting transfers trust to an office",
                back = "The author describes how institutions vet messengers. That makes " +
                    "authority portable between people.",
            ),
        )
    }

    @Test
    fun `a back that stands on its own is not flagged`() {
        assertNull(
            LumenCapture.backProblem(
                front = "Vetting transfers trust to an office",
                back = "An institution that certifies a messenger moves the question of " +
                    "trust from the person to the office. Authority then survives the " +
                    "individual who held it.",
            )
        )
    }

    /**
     * Trimming an over-long front used to stop wherever the eighth word fell.
     * A claim ending on a connective or a possessive reads as interrupted.
     */
    @Test
    fun `a trimmed front does not end on a dangling word`() {
        val trimmed = LumenCapture.trimFront(
            "Religious institutions transferred trust away from the individuals who"
        )
        assertFalse("Ends on a connective: $trimmed", trimmed.trimEnd('.').endsWith(" who"))
        assertFalse(trimmed.trimEnd('.').endsWith(" the"))
    }

    @Test
    fun `a trimmed front does not end on a possessive`() {
        val trimmed = LumenCapture.trimFront(
            "An adaptive tradition rested upon the spiritual experts' authority here"
        )
        assertFalse("Ends on a possessive: $trimmed", trimmed.endsWith("'"))
    }

    /** Nouns are not connectives: a claim may end on one. */
    @Test
    fun `a claim ending on a noun is left alone`() {
        assertEquals("Trust binds people", LumenCapture.trimFront("Trust binds people"))
    }

    @Test
    fun `a front already short enough is untouched`() {
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.trimFront("Fiction lets strangers cooperate"),
        )
    }
}
