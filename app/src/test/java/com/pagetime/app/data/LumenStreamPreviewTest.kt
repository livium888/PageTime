package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A capture streams JSON, and JSON arriving character by character is not
 * something to put in front of a reader. These pin what the wait is allowed to
 * show: the claim as it forms, or nothing.
 */
class LumenStreamPreviewTest {

    @Test
    fun `the front appears while the object is still open`() {
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.previewFront("""{"front": "Fiction lets strangers cooperate""")
        )
    }

    @Test
    fun `a finished object still previews its front`() {
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.previewFront(
                """{"front":"Fiction lets strangers cooperate","idea":"Shared stories bind."}"""
            )
        )
    }

    @Test
    fun `a reply that continues the primed opening is read from its first quote`() {
        // The prompt ends mid-object, so a model that continues rather than
        // restates begins inside the front's value.
        assertEquals(
            "Fiction lets strangers cooperate",
            LumenCapture.previewFront("""Fiction lets strangers cooperate", "idea": "Shared""")
        )
    }

    @Test
    fun `the eight word cap applies to the preview too`() {
        val preview = LumenCapture.previewFront(
            """{"front": "One two three four five six seven eight nine ten eleven"""
        )
        assertEquals(8, preview!!.split(" ").size)
    }

    @Test
    fun `nothing readable yet shows nothing`() {
        assertNull(LumenCapture.previewFront(""))
        assertNull(LumenCapture.previewFront("   "))
        assertNull(LumenCapture.previewFront("{"))
        assertNull(LumenCapture.previewFront("""{"fr"""))
    }

    @Test
    fun `a code fence does not become part of the title`() {
        val fenced = "```json\n" + """{"front": "Fiction lets strangers cooperate"""
        assertEquals("Fiction lets strangers cooperate", LumenCapture.previewFront(fenced))
    }
}
