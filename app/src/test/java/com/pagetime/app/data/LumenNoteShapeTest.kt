package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt asked for two sentences for as long as it was a rule, and got one.
 * Asking a 1B model for a length is asking it to count; asking it to fill a
 * named field is asking it to continue a pattern. These pin the two-field shape
 * and the promise that a prompt tailored before it keeps working.
 */
class LumenNoteShapeTest {

    @Test
    fun `idea and because are joined into the note`() {
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"Fiction lets strangers cooperate",""" +
                    """"idea":"Shared stories let people who never met act as one group.",""" +
                    """"because":"That is why a myth holds a nation where kinship cannot."}"""
            )

        assertEquals(
            "Shared stories let people who never met act as one group. " +
                "That is why a myth holds a nation where kinship cannot.",
            parsed!!.second
        )
    }

    @Test
    fun `a missing full stop between the two does not run them together`() {
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"A claim","idea":"The first sentence","because":"The second one."}"""
            )

        assertEquals("The first sentence. The second one.", parsed!!.second)
    }

    @Test
    fun `a two field note clears the back quality bar`() {
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"Fiction lets strangers cooperate",""" +
                    """"idea":"Shared stories let people who never met act as one group.",""" +
                    """"because":"That is why a myth holds a nation where kinship cannot."}"""
            )!!

        assertNull(LumenCapture.backProblem(parsed.first, parsed.second))
    }

    @Test
    fun `a prompt tailored before the schema still works`() {
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"A claim","back":"One sentence. And a second one."}"""
            )

        assertEquals("One sentence. And a second one.", parsed!!.second)
    }

    @Test
    fun `because alone is still a note, and still flagged as one sentence`() {
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"A claim worth keeping","idea":"","because":"Only the reason it holds survived here."}"""
            )!!

        assertEquals("Only the reason it holds survived here.", parsed.second)
        assertEquals(
            LumenCapture.BackProblem.SINGLE_SENTENCE,
            LumenCapture.backProblem(parsed.first, parsed.second)
        )
    }

    @Test
    fun `a reply cut off mid-note keeps what arrived`() {
        // The token cap can end the reply inside "because"; salvaging it beats
        // discarding a card that is most of the way there.
        val parsed =
            LumenCapture.parseDraft(
                """{"front":"A claim worth keeping","idea":"The first sentence lands.","because":"The second is cut"""
            )

        assertTrue(parsed!!.second.startsWith("The first sentence lands."))
        assertTrue(parsed.second.contains("The second is cut"))
    }

    @Test
    fun `two full sentences are no longer truncated at four hundred characters`() {
        val idea = "A".repeat(230) + "."
        val because = "B".repeat(230) + "."
        val parsed =
            LumenCapture.parseDraft("""{"front":"A claim","idea":"$idea","because":"$because"}""")!!

        assertTrue(
            "The note was cut to ${parsed.second.length} characters",
            parsed.second.length > 400
        )
        assertNull(LumenCapture.backProblem(parsed.first, parsed.second))
    }
}
