package com.pagetime.app.ui.screens.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Readium half of the sentence grab cannot be run without a phone and a web
 * view, but everything it decides before touching the page can: which span to
 * select, whether selecting it would change anything, and what the script it
 * sends looks like. Those are the parts that can be wrong in a way nobody
 * notices — a span that re-applies forever, a selector that arrives unescaped.
 */
class ReadiumSentenceGrabTest {

    private fun block(
        text: String,
        at: Int,
        selectionStart: Int = -1,
        selectionEnd: Int = -1
    ) = ReadiumSentenceGrab.Block(
        key = "body>p:nth-of-type(1)",
        text = text,
        at = at,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd
    )

    @Test
    fun `the sentence at the caret is the one chosen`() {
        val decision = assertNotNull(
            ReadiumSentenceGrab.decide(block("The cat sat. The dog ran.", at = 5))
        )
        assertEquals(0, decision.start)
        assertEquals(12, decision.end)
    }

    @Test
    fun `a selection that is already the sentence is left alone`() {
        // The guard against ping-pong: applying a span fires selectionchange,
        // which makes Readium rebuild its selection state, which brings the
        // menu back. Without this the snap would re-apply for ever.
        val decision = assertNotNull(
            ReadiumSentenceGrab.decide(
                block("The cat sat. The dog ran.", at = 5, selectionStart = 0, selectionEnd = 12)
            )
        )
        assertFalse(decision.needsApply)
    }

    @Test
    fun `a one-word selection is widened`() {
        val decision = assertNotNull(
            ReadiumSentenceGrab.decide(
                block("The cat sat. The dog ran.", at = 5, selectionStart = 4, selectionEnd = 7)
            )
        )
        assertTrue(decision.needsApply)
        assertEquals(12, decision.end)
    }

    @Test
    fun `an unusable block decides nothing`() {
        assertNull(ReadiumSentenceGrab.decide(block("   ", at = 1)))
        assertNull(ReadiumSentenceGrab.decide(block("", at = 0)))
    }

    @Test
    fun `stepping walks one sentence either way`() {
        val text = "One. Two. Three."
        val forward = ReadiumSentenceGrab.decideStep(text, 0, 4, forward = true)
        assertEquals(5, forward?.start)
        assertEquals(9, forward?.end)

        val back = ReadiumSentenceGrab.decideStep(text, 5, 9, forward = false)
        assertEquals(0, back?.start)
        assertEquals(4, back?.end)
    }

    @Test
    fun `stepping off either end of the block stops`() {
        val text = "One. Two."
        assertNull(ReadiumSentenceGrab.decideStep(text, 0, 4, forward = false))
        assertNull(ReadiumSentenceGrab.decideStep(text, 5, 9, forward = true))
    }

    @Test
    fun `stepping rejects a span that cannot be trusted`() {
        val text = "One. Two."
        assertNull(ReadiumSentenceGrab.decideStep(text, -1, 4, forward = true))
        assertNull(ReadiumSentenceGrab.decideStep(text, 4, 4, forward = true))
        assertNull(ReadiumSentenceGrab.decideStep(text, 4, 40, forward = true))
    }

    @Test
    fun `a read result is parsed into a block`() {
        val json = """
            {"key":"body>div:nth-of-type(1)>p:nth-of-type(2)","text":"Hello there.","at":6,
             "selStart":0,"selEnd":5}
        """.trimIndent()
        val parsed = assertNotNull(ReadiumSentenceGrab.parseRead(json))
        assertEquals("body>div:nth-of-type(1)>p:nth-of-type(2)", parsed.key)
        assertEquals("Hello there.", parsed.text)
        assertEquals(6, parsed.at)
        assertEquals(0, parsed.selectionStart)
        assertEquals(5, parsed.selectionEnd)
        assertEquals("Hello there.", parsed.head)
    }

    @Test
    fun `a read result with nothing in it is refused`() {
        assertNull(ReadiumSentenceGrab.parseRead(null))
        assertNull(ReadiumSentenceGrab.parseRead("null"))
        assertNull(ReadiumSentenceGrab.parseRead(""))
        assertNull(ReadiumSentenceGrab.parseRead("not json"))
        assertNull(ReadiumSentenceGrab.parseRead("""{"text":"hi","at":0}"""))
        assertNull(ReadiumSentenceGrab.parseRead("""{"key":"body","text":"","at":0}"""))
        assertNull(ReadiumSentenceGrab.parseRead("""{"key":"body","text":"hi","at":-1}"""))
        assertNull(ReadiumSentenceGrab.parseRead("""{"key":"body","text":"hi","at":99}"""))
    }

    @Test
    fun `a head longer than the block is the whole block`() {
        val parsed = ReadiumSentenceGrab.parseRead("""{"key":"body","text":"Short.","at":0}""")
        assertEquals("Short.", parsed?.head)
    }

    @Test
    fun `applying reports what the page said`() {
        assertTrue(ReadiumSentenceGrab.parseApplied("true"))
        assertTrue(ReadiumSentenceGrab.parseApplied("  true\n"))
        assertFalse(ReadiumSentenceGrab.parseApplied("false"))
        assertFalse(ReadiumSentenceGrab.parseApplied("null"))
        assertFalse(ReadiumSentenceGrab.parseApplied(null))
    }

    @Test
    fun `the apply script carries the offsets it was given`() {
        val script = ReadiumSentenceGrab.applyScript("body>p:nth-of-type(1)", "Head", 4, 17)
        assertTrue(script.contains("if (sn === null && 4 < pos + len)"))
        assertTrue(script.contains("if (en === null && 17 <= pos + len)"))
        assertTrue(script.contains("body>p:nth-of-type(1)"))
        assertTrue(script.contains("range.setStart(sn, so)"))
    }

    @Test
    fun `a quote in a block's text cannot break out of the script`() {
        // The head is a slice of the book, so it can contain anything at all.
        val script = ReadiumSentenceGrab.applyScript(
            key = "body>p:nth-of-type(1)\"",
            head = "He said \"stop.\" Then",
            start = 0,
            end = 4
        )
        assertTrue(script.contains("\\\"stop.\\\""))
        // The raw, unescaped text must not appear: ending the JavaScript string
        // literal early would leave the rest of the sentence to run as code.
        assertFalse(script.contains("said \"stop"))
    }

    @Test
    fun `a newline in a block's text cannot break out of the script`() {
        val script = ReadiumSentenceGrab.applyScript(
            key = "body",
            head = "One\nTwo",
            start = 0,
            end = 3
        )
        assertTrue(script.contains("One\\nTwo"))
    }

    @Test
    fun `both scripts are balanced enough to be JavaScript`() {
        // Cheap, and it catches the class of mistake no compiler here can: a
        // script assembled from a Kotlin template with a brace left behind.
        for ((name, script) in listOf(
            "read" to ReadiumSentenceGrab.READ_SCRIPT,
            "apply" to ReadiumSentenceGrab.applyScript("body", "h", 0, 1)
        )) {
            assertEquals(
                script.count { it == '{' },
                script.count { it == '}' },
                "$name script has unbalanced braces"
            )
            assertEquals(
                script.count { it == '(' },
                script.count { it == ')' },
                "$name script has unbalanced parentheses"
            )
            assertTrue(script.startsWith("(function ()"), "$name script is not an IIFE")
            assertTrue(script.endsWith("})()"), "$name script does not close its IIFE")
        }
    }
}
