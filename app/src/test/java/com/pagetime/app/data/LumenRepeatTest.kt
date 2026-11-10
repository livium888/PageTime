package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture window is a fixed radius around the reading position and far
 * wider than a phone page, so two captures a page apart are handed passages
 * that overlap heavily. The model then names the same idea twice, which is not
 * a second card. These pin what counts as the same idea and the promise that
 * noticing it never costs the reader the card they got.
 */
class LumenRepeatTest {

    private val filed = listOf("Fiction lets strangers cooperate")
    private val twoSentences =
        "Shared stories let people who have never met act as one group. " +
            "That is why a myth can hold a nation together where kinship cannot."

    @Test
    fun `the same claim word for word is a repeat`() {
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.repeatOf("Fiction lets strangers cooperate.", filed)
        )
    }

    @Test
    fun `a reworded version of a filed claim is a repeat`() {
        assertNotNull(LumenCapture.repeatOf("Fiction lets strangers cooperate at scale", filed))
    }

    @Test
    fun `a different idea from the same passage is not a repeat`() {
        assertNull(LumenCapture.repeatOf("Myths outlive the people who tell them", filed))
    }

    @Test
    fun `an empty box repeats nothing`() {
        assertNull(LumenCapture.repeatOf("Fiction lets strangers cooperate", emptyList()))
    }

    @Test
    fun `two claim words in common is a shared subject, not a repeated claim`() {
        // The ratio is measured against the shorter front, so a front with two
        // claim words needed only those two to coincide to read as a repeat.
        // Every false match costs a good card: the capture is re-asked for a
        // DIFFERENT idea, which is by construction the model's second choice,
        // and the second choice then wins. With twelve fronts to collide with,
        // a filling box quietly becomes a box of runner-up ideas.
        assertNull(
            LumenCapture.repeatOf("Money is memory", listOf("Memory shapes money"))
        )
        assertNull(
            LumenCapture.repeatOf("Trust enables trade", listOf("Trust enables cooperation"))
        )
    }

    @Test
    fun `a genuine repeat is still caught`() {
        assertNotNull(
            LumenCapture.repeatOf(
                "Fiction lets strangers cooperate",
                listOf("Fiction lets strangers work together"),
            )
        )
        assertNotNull(
            LumenCapture.repeatOf(
                "Writing outlives its author",
                listOf("Writing outlives its author"),
            )
        )
    }
}
