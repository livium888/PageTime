package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenAddressTest {

    @Test
    fun `first card in an empty box gets address 1`() {
        assertEquals("1", LumenAddress.nextAddress(emptyList(), null))
    }

    @Test
    fun `cards continue the line`() {
        val existing = listOf("1", "2", "3")
        assertEquals("4", LumenAddress.nextAddress(existing, null))
    }

    @Test
    fun `gaps in numbering are not reused`() {
        val existing = listOf("1", "3")
        assertEquals("4", LumenAddress.nextAddress(existing, null))
    }

    @Test
    fun `filing behind a slip branches with a letter`() {
        val existing = listOf("21", "22")
        assertEquals("21a", LumenAddress.nextAddress(existing, "21"))
    }

    @Test
    fun `filing behind an already-branched slip takes the next letter`() {
        val existing = listOf("21", "21a", "21b", "22")
        assertEquals("21c", LumenAddress.nextAddress(existing, "21"))
    }

    @Test
    fun `sub-notes nest with alternating depth like real Luhmann addresses`() {
        val existing = listOf("21", "21a")
        assertEquals("21a1", LumenAddress.nextAddress(existing, "21a"))
        assertEquals("21a2", LumenAddress.nextAddress(existing + "21a1", "21a"))
    }

    @Test
    fun `exhausted alphabet branches deeper under the last letter`() {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        val taken = (0 until 26).map { "21${alphabet[it]}" }.toSet()
        // 21a…21z all taken: the next child lives under 21z (21z1).
        assertEquals("21z1", LumenAddress.nextAddress(taken.toList(), "21"))
    }

    @Test
    fun `relative part strips the box prefix`() {
        assertEquals("2a7", LumenAddress.relativePart("21/2a7"))
        assertEquals("3", LumenAddress.relativePart("3"))
    }

    @Test
    fun `shelf order matches Luhmann filing order`() {
        val sorted = listOf("22", "21b", "21a1", "21a", "210", "21", "23")
            .sortedWith(LumenAddress.COMPARATOR)
        assertEquals(
            listOf("21", "21a", "21a1", "21b", "22", "23", "210"),
            sorted
        )
    }

    @Test
    fun `lettered branches sort after their number`() {
        val sorted = listOf("21b", "21", "21a").sortedWith(LumenAddress.COMPARATOR)
        assertEquals(listOf("21", "21a", "21b"), sorted)
    }
}

class LumenLinksTest {

    @Test
    fun `links round-trip through json`() {
        val links = listOf("a", "b", "c")
        assertEquals(links, LumenCapture.linksFromJson(LumenCapture.linksToJson(links)))
    }

    @Test
    fun `malformed links json degrades to empty`() {
        assertEquals(emptyList<String>(), LumenCapture.linksFromJson("not json"))
        assertEquals(emptyList<String>(), LumenCapture.linksFromJson(""))
    }

    @Test
    fun `empty links list serializes to empty array`() {
        assertEquals("[]", LumenCapture.linksToJson(emptyList()))
    }
}

class LumenCoachTest {

    private fun card(
        id: String,
        links: List<String> = emptyList(),
        snippets: Int = 0,
        back: String = ""
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        return LumenCardEntity(
            id = id,
            bookId = "b",
            box = 1,
            indexNumber = id,
            front = "Note $id",
            back = back,
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = LumenCapture.snippetsToJson(
                (0 until snippets).map { LumenSnippet("s$it", now, null) }
            ),
            linksJson = LumenCapture.linksToJson(links),
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `empty box gets the first-capture step`() {
        assertTrue(LumenCoach.nextStep(emptyList())!!.contains("New Lumen card"))
    }

    @Test
    fun `links-less box is told to link first`() {
        val step = LumenCoach.nextStep(listOf(card("1"), card("2")))
        assertTrue(step!!.contains("Link"))
    }

    @Test
    fun `linked box moves to re-encounter advice`() {
        val step = LumenCoach.nextStep(
            listOf(card("1", links = listOf("2")), card("2", links = listOf("1")), card("3"))
        )
        assertTrue(step!!.contains("Context") || step.contains("re-encounter", ignoreCase = true))
    }

    @Test
    fun `every lesson has a practice step and body`() {
        LumenCoach.lessons.forEach { lesson ->
            assertTrue(lesson.body.isNotBlank())
            assertTrue(lesson.practice.isNotBlank())
        }
        assertTrue(LumenCoach.lessons.size >= 6)
    }
}
