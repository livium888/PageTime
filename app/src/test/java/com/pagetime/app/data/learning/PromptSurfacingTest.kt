package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a prompt is allowed to interrupt somebody who is reading.
 *
 * Every mistake available here is silent and makes the feature feel broken
 * rather than buggy: a question asked before its answer has been read, two
 * questions at once, a question that comes back after being dismissed, or five
 * arriving in a heap because the reader skipped ahead.
 */
class PromptSurfacingTest {

    private val prompts = listOf(
        SurfaceablePrompt("a", 0.2f),
        SurfaceablePrompt("b", 0.5f),
        SurfaceablePrompt("c", 0.8f),
    )

    @Test
    fun `nothing surfaces before its passage has been read`() {
        assertNull(PromptSurfacing.next(prompts, progression = 0.1f))
    }

    @Test
    fun `a prompt surfaces once the reader is past its passage`() {
        assertEquals("a", PromptSurfacing.next(prompts, progression = 0.25f)?.id)
    }

    @Test
    fun `arriving exactly at the end of the passage is far enough`() {
        assertEquals("a", PromptSurfacing.next(prompts, progression = 0.2f)?.id)
    }

    /**
     * The reader who skips half a chapter gets one question, not three. The
     * unit of interruption is one.
     */
    @Test
    fun `skipping ahead does not produce a heap`() {
        val shown = PromptSurfacing.next(prompts, progression = 0.95f)
        assertEquals("a", shown?.id)
    }

    @Test
    fun `the next one waits until the last is judged`() {
        val judged = setOf("a")
        assertEquals("b", PromptSurfacing.next(prompts, progression = 0.95f, judged)?.id)
        assertEquals("c", PromptSurfacing.next(prompts, progression = 0.95f, judged + "b")?.id)
        assertNull(PromptSurfacing.next(prompts, progression = 0.95f, judged + "b" + "c"))
    }

    @Test
    fun `a dismissed prompt does not come back`() {
        // The database knows too, but a row updates asynchronously, and a
        // prompt flickering back for a moment reads as unfinished software.
        assertNull(PromptSurfacing.next(listOf(prompts[0]), 0.9f, judged = setOf("a")))
    }

    @Test
    fun `the reader can be told how much is still coming`() {
        assertEquals(3, PromptSurfacing.remaining(prompts, progression = 0f))
        assertEquals(2, PromptSurfacing.remaining(prompts, progression = 0.3f))
        assertEquals(0, PromptSurfacing.remaining(prompts, progression = 1f))
        // Judged ones are behind the reader whatever the fraction says.
        assertEquals(1, PromptSurfacing.remaining(prompts, 0.3f, judged = setOf("b")))
    }

    @Test
    fun `a chapter with no prompts never interrupts`() {
        assertNull(PromptSurfacing.next(emptyList(), progression = 0.5f))
        assertEquals(0, PromptSurfacing.remaining(emptyList(), progression = 0.5f))
    }

    @Test
    fun `prompts out of order still surface in reading order`() {
        // pendingForChapter orders by fraction, but nothing downstream should
        // depend on the query staying that way.
        val jumbled = listOf(
            SurfaceablePrompt("late", 0.9f),
            SurfaceablePrompt("early", 0.1f),
        )
        assertEquals("early", PromptSurfacing.next(jumbled, progression = 1f)?.id)
    }
}
