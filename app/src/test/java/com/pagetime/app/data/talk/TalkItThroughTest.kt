package com.pagetime.app.data.talk

import com.pagetime.app.data.talk.TalkItThrough.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TalkItThroughTest {

    private fun receipt(exchanges: Int, verdict: Verdict = Verdict.UNDERSTOOD) =
        TalkItThrough.Receipt("talk-abc12", exchanges, verdict, emptyList())

    @Test
    fun `longer stretches earn more questions`() {
        assertEquals(1, TalkItThrough.questionsFor(800))
        assertEquals(2, TalkItThrough.questionsFor(2_500))
        assertEquals(2, TalkItThrough.questionsFor(7_999))
        assertEquals(3, TalkItThrough.questionsFor(8_000))
        assertEquals(6, TalkItThrough.estimateMinutes(2))
    }

    @Test
    fun `the prompt carries the passage, what you marked, the rules and the receipt with its code`() {
        val p = TalkItThrough.buildPrompt(
            "Why We Sleep", "Chapter 4", "Adenosine builds up.", listOf("adenosine builds"), listOf("sleep debt"), 2, "talk-abc12",
        )
        assertTrue(p.contains("“Why We Sleep” (Chapter 4)"))
        assertTrue(p.contains("Adenosine builds up."))
        assertTrue(p.contains("adenosine builds"))
        assertTrue(p.contains("sleep debt"))
        assertTrue(p.contains("Ask me 2 core questions, ONE at a time"))
        assertTrue(p.contains("Never give me the answer first"))
        assertTrue(p.contains("PAGETIME RECEIPT"))
        assertTrue(p.contains("code: talk-abc12"))
    }

    @Test
    fun `a long passage is cut at a sentence end`() {
        val long = "A sentence goes here. ".repeat(600)
        val cut = TalkItThrough.excerpt(long)
        assertTrue(cut.length <= TalkItThrough.MAX_PASSAGE_CHARS + 3)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.dropLast(2).trimEnd().endsWith("."))
    }

    @Test
    fun `codes are short and not all the same`() {
        val a = TalkItThrough.newCode(Random(1))
        val b = TalkItThrough.newCode(Random(2))
        assertTrue(a.startsWith("talk-") && a.length == 10)
        assertTrue(a != b)
    }

    @Test
    fun `a receipt is read however the AI formats it`() {
        val reply = """
            Great talking! Here is your receipt:

            **PAGETIME RECEIPT**
            code: talk-abc12
            exchanges: 4
            verdict: Partly
            shaky: the role of adenosine; why REM matters.
        """.trimIndent()
        val r = TalkItThrough.parseReceipt(reply, "talk-abc12").getOrThrow()
        assertEquals(4, r.exchanges)
        assertEquals(Verdict.PARTLY, r.verdict)
        assertEquals(listOf("the role of adenosine", "why REM matters"), r.shaky)
        val bold = "PAGETIME RECEIPT\n**code:** talk-ABC12\n**exchanges:** 3 answers\n**verdict:** understood\nshaky: none"
        val r2 = TalkItThrough.parseReceipt(bold, "talk-abc12").getOrThrow()
        assertEquals(3, r2.exchanges)
        assertEquals(emptyList<String>(), r2.shaky)
    }

    @Test
    fun `a receipt for another conversation, or with no code, is refused with a way forward`() {
        val other = "PAGETIME RECEIPT\ncode: talk-zzzzz\nexchanges: 3\nverdict: understood"
        val failure = TalkItThrough.parseReceipt(other, "talk-abc12").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("code: talk-abc12"))
        assertTrue(TalkItThrough.parseReceipt("Sounds good, well done!", "talk-abc12").isFailure)
        assertTrue(TalkItThrough.parseReceipt("code: talk-abc12\nverdict: understood", "talk-abc12").isFailure)
    }

    @Test
    fun `credit is the smallest of the stopwatch, the exchanges and the cap`() {
        val c = TalkItThrough.credit(10 * 60 + 12, receipt(4))
        assertEquals(6 * 60L, c.seconds)
        assertNull(c.withheld)
        // Left the chat for three hours: the exchanges limit it.
        assertEquals(6 * 60L, TalkItThrough.credit(3 * 3600L, receipt(4)).seconds)
        // A short conversation earns what it took.
        assertEquals(200L, TalkItThrough.credit(200L, receipt(4)).seconds)
        // A very long, deep one is capped.
        assertEquals(20 * 60L, TalkItThrough.credit(40 * 60L, receipt(20)).seconds)
    }

    @Test
    fun `nothing is earned for a receipt pasted at once, no answers, or no engagement`() {
        assertEquals(0L, TalkItThrough.credit(25L, receipt(4)).seconds)
        assertTrue(TalkItThrough.credit(25L, receipt(4)).withheld!!.contains("faster"))
        assertEquals(0L, TalkItThrough.credit(600L, receipt(0)).seconds)
        assertEquals(0L, TalkItThrough.credit(480L, receipt(3, Verdict.DID_NOT_ENGAGE)).seconds)
        // Not understanding yet is effort, and is credited.
        assertEquals(270L, TalkItThrough.credit(480L, receipt(3, Verdict.NOT_YET)).seconds)
    }

    @Test
    fun `a conversation in progress round-trips, and the clock reads m and ss`() {
        val p = TalkItThrough.Pending("talk-abc12", 1_700_000_000_000L, "Chapter 4")
        assertEquals(p, TalkItThrough.decode(TalkItThrough.encode(p)))
        assertNull(TalkItThrough.decode("nonsense"))
        assertEquals("7:05", TalkItThrough.clock(425))
    }
}
