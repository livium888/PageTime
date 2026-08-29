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

    @Test
    fun `descendants include lettered children but not numeric neighbors`() {
        // 210 is a sibling of 21 in Luhmann's grid, not a child.
        assertTrue(LumenAddress.isDescendantOf("21a", "21"))
        assertTrue(LumenAddress.isDescendantOf("21a1", "21"))
        assertTrue(LumenAddress.isDescendantOf("21z3", "21"))
        assertTrue(!LumenAddress.isDescendantOf("210", "21"))
        assertTrue(!LumenAddress.isDescendantOf("22", "21"))
        assertTrue(!LumenAddress.isDescendantOf("21", "21"))
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

class LumenRegisterThreadTest {

    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $indexNumber",
        back: String = "",
        keywords: String = ""
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        return LumenCardEntity(
            id = id,
            bookId = "b",
            box = 1,
            indexNumber = indexNumber,
            front = front,
            back = back,
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = "[]",
            keywords = keywords,
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `register maps keywords to entry addresses`() {
        val register = LumenRegister.build(
            listOf(
                card("1", "21", keywords = "power sovereignty"),
                card("2", "21a", keywords = "power"),
                card("3", "30", keywords = "biology evolution")
            )
        )
        val power = register.first { it.keyword == "power" }
        assertEquals(listOf("21", "21a"), power.addresses)
    }

    @Test
    fun `register skips cards without addresses`() {
        val register = LumenRegister.build(listOf(card("1", "")))
        assertTrue(register.isEmpty())
    }

    @Test
    fun `thread pulls the whole line in shelf order`() {
        val root = card("1", "21")
        val all = listOf(
            root,
            card("2", "22"),
            card("3", "21b"),
            card("4", "21a"),
            card("5", "210")
        )
        val steps = LumenThread.pull(root, all)
        assertEquals(listOf("21", "21a", "21b"), steps.map { it.card.indexNumber })
        // 210 and 22 are NOT part of 21's thread.
    }

    @Test
    fun `render produces an indented outline with addresses`() {
        val root = card("1", "21", front = "Power", back = "The core idea")
        val child = card("2", "21a", front = "Sovereignty")
        val text = LumenThread.render(LumenThread.pull(root, listOf(root, child)))
        assertTrue(text.contains("21  Power"))
        assertTrue(text.contains("    The core idea"))
        assertTrue(text.contains("  21a  Sovereignty"))
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

class LumenSourcesTest {

    private fun card(
        id: String,
        bookId: String,
        indexNumber: String,
        box: Int = 1,
        front: String = "Note $id",
        quote: String = ""
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        return LumenCardEntity(
            id = id,
            bookId = bookId,
            box = box,
            indexNumber = indexNumber,
            front = front,
            back = "",
            quote = quote,
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = "[]",
            keywords = "",
            createdAt = now,
            updatedAt = now
        )
    }

    private val books = listOf(
        LumenSources.BookMeta("b1", "Power", "Foucault"),
        LumenSources.BookMeta("b2", "Anti-Oedipus", "Deleuze")
    )

    @Test
    fun `cards group by their source book`() {
        val sources = LumenSources.group(
            listOf(card("1", "b1", "21"), card("2", "b1", "22"), card("3", "b2", "1")),
            books
        )
        assertEquals(2, sources.size)
        assertEquals("Anti-Oedipus", sources[0].title) // sorted by title
        assertEquals("Power", sources[1].title)
    }

    @Test
    fun `desk cards without a source are excluded`() {
        val sources = LumenSources.group(
            listOf(card("1", "", "1"), card("2", "b1", "21")),
            books
        )
        assertEquals(1, sources.size)
        assertEquals("b1", sources[0].bookId)
    }

    @Test
    fun `cards within a source are in shelf order`() {
        val sources = LumenSources.group(
            listOf(card("1", "b1", "22"), card("2", "b1", "21"), card("3", "b1", "21a")),
            books
        )
        assertEquals(listOf("21", "21a", "22"), sources[0].cards.map { it.indexNumber })
    }

    @Test
    fun `unknown source falls back to a placeholder title`() {
        val sources = LumenSources.group(listOf(card("1", "deleted-book", "21")), books)
        assertEquals("Unknown source", sources[0].title)
    }

    @Test
    fun `render includes the bibliography line and cited notes`() {
        val source = LumenSources.Source(
            bookId = "b1",
            title = "Power",
            author = "Foucault",
            cards = listOf(card("1", "b1", "21", quote = "Power is everywhere."))
        )
        val text = LumenSources.render(source)
        assertTrue(text.contains("Power — Foucault"))
        assertTrue(text.contains("21  Note 1"))
        assertTrue(text.contains("Power is everywhere."))
    }

    @Test
    fun `render omits the author when missing`() {
        val source = LumenSources.Source("b1", "Untitled", "", emptyList())
        // No cards and no author: the render is just the bibliography line.
        assertEquals("Untitled", LumenSources.render(source))
    }
}

class LumenSearchTest {

    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $id",
        back: String = "",
        quote: String = "",
        keywords: String = ""
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        return LumenCardEntity(
            id = id,
            bookId = "b1",
            box = 1,
            indexNumber = indexNumber,
            front = front,
            back = back,
            quote = quote,
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = "[]",
            keywords = keywords,
            createdAt = now,
            updatedAt = now
        )
    }

    private val box: List<LumenCardEntity> = listOf(
        card("1", "21", front = "Sovereign power grows", keywords = "power sovereignty"),
        card("2", "21a", front = "Discipline spreads", back = "via institutions"),
        card("3", "22", front = "Markets price risk", quote = "The market prices risk."),
        card("4", "23", front = "Unrelated note")
    )

    private fun ids(cards: List<LumenCardEntity>): List<String> = cards.map { it.id }

    @Test
    fun `blank query returns the list unchanged`() {
        assertEquals(box, LumenSearch.filter(box, ""))
        assertEquals(box, LumenSearch.filter(box, "   "))
    }

    @Test
    fun `one term matches across front back quote keywords and address`() {
        assertEquals(listOf("1"), ids(LumenSearch.filter(box, "sovereign")))
        assertEquals(listOf("2"), ids(LumenSearch.filter(box, "institutions")))
        assertEquals(listOf("3"), ids(LumenSearch.filter(box, "prices risk")))
        assertEquals(listOf("1"), ids(LumenSearch.filter(box, "sovereignty")))
    }

    @Test
    fun `multiple terms AND together`() {
        assertEquals(listOf("3"), ids(LumenSearch.filter(box, "market risk")))
        assertTrue(LumenSearch.filter(box, "market sovereignty").isEmpty())
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("1"), ids(LumenSearch.filter(box, "SOVEREIGN")))
    }

    @Test
    fun `an address term finds its whole line`() {
        assertEquals(listOf("1", "2"), ids(LumenSearch.filter(box, "21")))
    }
}
