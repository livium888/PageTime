package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device grader. Its job is to be honest about what it did and did not
 * judge, so most of these tests are about what it refuses to claim.
 */
class ExplainBackGradingTest {

    private val reply =
        """{"verdict": "partly", "right": "You correctly said the tide is caused by the moon.",
           |"missed": "You left out the second bulge on the far side of the earth.",
           |"better": "Say what happens to the water opposite the moon.",
           |"simple": "The moon pulls the sea towards it. The earth is pulled away from the
           |water on the far side, so there is a bulge there too."}""".trimMargin()

    @Test
    fun `a full reply becomes an evaluation`() {
        val ev = ExplainBackGrading.parse(reply)
        assertNotNull(ev)
        requireNotNull(ev)
        assertEquals(ExplainBackGrading.Verdict.PARTLY.score, ev.overallScore, 0.001f)
        assertTrue(ev.whatTheyGotRight.contains("moon"))
        assertTrue(ev.whatTheyMissed.contains("bulge"))
        assertTrue(ev.suggestedImprovement.isNotBlank())
        assertTrue(ev.simplerVersion.isNotBlank())
    }

    @Test
    fun `the local grader never claims a three-part breakdown`() {
        // The whole reason this grader exists is that a 1B model asked for
        // three independent five-point scores returns three numbers it never
        // worked out. Anything that starts filling these in is a regression.
        val ev = requireNotNull(ExplainBackGrading.parse(reply))
        assertNull(ev.accuracy)
        assertNull(ev.completeness)
        assertNull(ev.clarity)
        assertTrue("A local mark must not look like a measurement", !ev.hasBreakdown)
    }

    @Test
    fun `only a solid verdict counts as mastered`() {
        // countMastered() in ExplanationDao selects overallScore >= 3.5.
        fun scoreOf(v: String) =
            requireNotNull(ExplainBackGrading.parse("""{"verdict":"$v","right":"Yes."}""")).overallScore
        assertTrue(scoreOf("solid") >= 3.5f)
        assertTrue(scoreOf("partly") < 3.5f)
        assertTrue(scoreOf("off") < 3.5f)
    }

    @Test
    fun `a verdict wrapped in a sentence is still read`() {
        // Asked for one word, a small model routinely answers with a clause.
        val ev = ExplainBackGrading.parse(
            """{"verdict": "solid - they clearly understand it", "right": "You named the cause."}"""
        )
        assertEquals(ExplainBackGrading.Verdict.SOLID.score, requireNotNull(ev).overallScore, 0.001f)
    }

    @Test
    fun `off is matched as a word, not as a substring`() {
        // "offered", "office", "off-topic" all contain "off". Matching loosely
        // would fail answers whose feedback merely mentions one of them.
        val ev = ExplainBackGrading.parse(
            """{"verdict": "solid", "right": "You offered a clear cause and effect."}"""
        )
        assertEquals(ExplainBackGrading.Verdict.SOLID.score, requireNotNull(ev).overallScore, 0.001f)
    }

    @Test
    fun `prose without a verdict is still feedback`() {
        // A grader that wrote something useful and dropped the one-word field
        // has still helped. Partly is the honest reading of an absent mark.
        val ev = ExplainBackGrading.parse(
            """{"right": "You got the mechanism.", "missed": "Not the timing."}"""
        )
        assertEquals(ExplainBackGrading.Verdict.PARTLY.score, requireNotNull(ev).overallScore, 0.001f)
    }

    @Test
    fun `a verdict with no feedback is refused`() {
        // A bare mark is the one thing this grader must not produce: it is a
        // number with nothing behind it, which is what the scores were dropped
        // for in the first place.
        assertNull(ExplainBackGrading.parse("""{"verdict": "solid"}"""))
        assertNull(ExplainBackGrading.parse("   "))
        // Answered in the right shape and said nothing. The word lookup had
        // exactly this bug: a present-but-empty field read as an answer.
        assertNull(ExplainBackGrading.parse("""{"verdict":"partly","right":"","missed":""}"""))
    }

    @Test
    fun `a reply cut off mid-field keeps what arrived`() {
        val ev = ExplainBackGrading.parse(
            """{"verdict": "off", "right": "You tried the right kind of explanation.",
               |"missed": "The passage says the opposite of what you wrote, because""".trimMargin()
        )
        requireNotNull(ev)
        assertEquals(ExplainBackGrading.Verdict.OFF.score, ev.overallScore, 0.001f)
        assertTrue(ev.whatTheyMissed.startsWith("The passage says"))
    }

    @Test
    fun `the source window centres on the concept's own quote`() {
        val source = "A".repeat(6_000) + " THE ANCHOR SENTENCE. " + "Z".repeat(6_000)
        val window = ExplainBackGrading.sourceWindow(source, "THE ANCHOR SENTENCE")
        assertTrue("The concept's quote must survive the trim", window.contains("THE ANCHOR"))
        assertTrue(window.length <= ExplainBackGrading.MAX_SOURCE_CHARS)
    }

    @Test
    fun `without an anchor the window keeps the most recently read text`() {
        val source = "OLD ".repeat(2_000) + "NEWEST TEXT."
        val window = ExplainBackGrading.sourceWindow(source, null)
        assertTrue(window.endsWith("NEWEST TEXT."))
        assertTrue(window.length <= ExplainBackGrading.MAX_SOURCE_CHARS)
    }

    @Test
    fun `a short source is passed through whole`() {
        val source = "One short paragraph about the tides."
        assertEquals(source, ExplainBackGrading.sourceWindow(source, null))
    }

    @Test
    fun `the widest grading prompt fits the token budget`() {
        // Explain Back is the only ask that carries two texts at once. If the
        // pair overflows, MediaPipe aborts the process rather than returning
        // an error, so the invariant is pinned here.
        val prompt = ExplainBackGrading.prompt(
            conceptLabel = "A reasonably long concept label",
            keyPoints = listOf("k".repeat(80), "p".repeat(80), "q".repeat(80)),
            source = "word ".repeat(20_000),
            userExplanation = "e".repeat(ExplainBackGrading.MAX_EXPLANATION_CHARS * 2),
            anchor = null
        )
        val tokens = LlmTokenBudget.estimateTokens(prompt)
        val budget = LlmTokenBudget.inputBudget(ExplainBackGrading.REPLY_TOKENS)
        assertTrue(
            "Grading prompt needs ~$tokens tokens but only $budget are available",
            tokens <= budget
        )
    }
}
