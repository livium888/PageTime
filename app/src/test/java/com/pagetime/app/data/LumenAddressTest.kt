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
    fun `filing repeatedly behind a lettered slip keeps the same line`() {
        val existing = listOf("1", "1a", "1a1", "1a2", "1a1a")
        assertEquals("1a3", LumenAddress.nextAddress(existing, "1a"))
    }

    @Test
    fun `picker address is resolved case insensitively before generating child`() {
        val existing = listOf("8", "1A")
        val selected = LumenAddress.resolveExisting(existing, "1a")
        assertEquals("1A", selected)
        assertEquals("1A1", LumenAddress.nextAddress(existing, selected))
    }

    @Test
    fun `adding another card behind 1a keeps growing that exact line`() {
        // The reported bug: filing behind a branched slip (1a) must continue
        // to produce new addresses on THAT line, never the same one and never
        // a detached top-level number.
        var taken = listOf("1", "1a")
        assertEquals("1a1", LumenAddress.nextAddress(taken, "1a"))
        taken = taken + "1a1"
        // Again behind 1a → the next sibling on the same line.
        assertEquals("1a2", LumenAddress.nextAddress(taken, "1a"))
        taken = taken + "1a2"
        // Behind the branch root again → a sibling of the letters, not a repeat.
        assertEquals("1b", LumenAddress.nextAddress(taken, "1"))
        // Behind a grandchild → nests deeper on the 1a line.
        assertEquals("1a1a", LumenAddress.nextAddress(taken, "1a1"))
        taken = taken + "1b" + "1a1a"
        val all = taken.toSet()
        assertEquals(taken.size, all.size)
    }

    @Test
    fun `filing at arbitrary depth never repeats an existing address`() {
        // Simulate "a million cards one after another": each filing, whether a
        // top-level continuation or a branch behind an existing slip (going
        // arbitrarily deep), must yield a brand-new stable address. The concern
        // is that a scheme breaks past a few levels — it must hold indefinitely.
        var taken = emptyList<String>()
        val seen = mutableSetOf<String>()
        repeat(150) { i ->
            val target = when {
                taken.isEmpty() -> null
                i % 4 == 0 -> null // top-level continuation
                i % 10 == 0 -> taken.last() // follow the newest card down a line
                else -> taken[(i * 7 + 3) % taken.size] // branch behind something
            }
            val next = LumenAddress.nextAddress(taken, target)
            assertTrue("Reused $next at step $i", next !in seen)
            seen.add(next)
            taken = taken + next
        }
        assertTrue(taken.size == seen.size && taken.size == 150)
    }

    @Test
    fun `shelf order keeps deep branches adjacent and numeric siblings after their root`() {
        val cards = listOf(
            card("210", "Numeric sibling"),
            card("22", "Main line b"),
            card("21a", "Child a"),
            card("2", "Main line two"),
            card("21", "Main line"),
            card("21a1", "Grandchild"),
            card("10", "Tenth main"),
            card("21b", "Child b"),
            card("21a1a", "Great-grandchild")
        )
        val order = LumenAddress.shelfOrder(cards).map { it.indexNumber }
        assertEquals(
            listOf("2", "10", "21", "21a", "21a1", "21a1a", "21b", "22", "210"),
            order
        )
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

    @Test
    fun `thread path walks the branch for a nested card`() {
        val cards = listOf(
            card("21", "Systems persist"),
            card("21a", "Rituals repeat the pattern"),
            card("21a1", "Rites bind groups"),
            card("22", "Networks hold")
        )
        val path = LumenAddress.threadPath("21a1", cards)
        assertEquals(
            listOf("21" to "Systems persist", "21a" to "Rituals repeat the pattern", "21a1" to "Rites bind groups"),
            path
        )
    }

    @Test
    fun `thread path for a top-level card is just itself`() {
        val cards = listOf(card("21", "Systems persist"), card("22", "Networks hold"))
        assertEquals(listOf(cardOf("21", "Systems persist")), LumenAddress.threadPath("21", cards))
    }

    @Test
    fun `thread path skips missing middle slips but keeps the numeric root`() {
        // 21a is only implied (deleted); the path shows 21 then 21a1.
        val cards = listOf(
            card("21", "Systems persist"),
            card("21a1", "Rites bind groups"),
            card("22", "Networks hold")
        )
        val path = LumenAddress.threadPath("21a1", cards)
        assertEquals(
            listOf("21" to "Systems persist", "21a1" to "Rites bind groups"),
            path
        )
    }

    @Test
    fun `thread path ignores numeric siblings and is empty for blank address`() {
        val cards = listOf(card("21", "Systems persist"), card("210", "A sibling, not a child"))
        assertEquals(listOf(cardOf("21", "Systems persist")), LumenAddress.threadPath("21", cards))
        assertTrue(LumenAddress.threadPath("", cards).isEmpty())
    }

    @Test
    fun `branch depth counts proper ancestors within the line`() {
        val cards = listOf(
            card("1", "Root one"),
            card("2", "Systems persist"),
            card("2a", "Rituals repeat the pattern"),
            card("2a1", "Rites bind groups"),
            card("2b", "Another child"),
            card("210", "Sibling, not a child of 21"),
            card("22", "Next main line")
        )
        assertEquals(0, LumenAddress.branchDepth("1", cards))
        assertEquals(0, LumenAddress.branchDepth("2", cards))
        assertEquals(1, LumenAddress.branchDepth("2a", cards))
        assertEquals(1, LumenAddress.branchDepth("2b", cards))
        assertEquals(2, LumenAddress.branchDepth("2a1", cards))
        // 210 is a sibling of 2 in Luhmann's grid, so depth stays 0.
        assertEquals(0, LumenAddress.branchDepth("210", cards))
        assertEquals(0, LumenAddress.branchDepth("22", cards))
    }

    @Test
    fun `branch depth is zero for blank or absent addresses`() {
        val cards = listOf(card("21", "Systems persist"))
        assertEquals(0, LumenAddress.branchDepth("", cards))
        assertEquals(0, LumenAddress.branchDepth("99", cards))
    }

    private fun card(address: String, front: String): LumenCardEntity =
        LumenCardEntity(
            id = "id-$address",
            bookId = "",
            box = 1,
            indexNumber = address,
            front = front,
            back = "",
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            createdAt = 0,
            updatedAt = 0
        )

    private fun cardOf(address: String, front: String): Pair<String, String> = address to front
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
        back: String = "",
        isHub: Boolean = false
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
            isHub = isHub,
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

    @Test
    fun `six growing cards with no hub are told to file one`() {
        val cards = (1..6).map { card("$it", links = listOf("1"), snippets = 1) }
        val step = LumenCoach.nextStep(cards)
        assertTrue(step!!.contains("hub", ignoreCase = true))
    }

    @Test
    fun `a marked hub is recognized as the index head`() {
        val cards = (1..6).map { card("$it", links = listOf("1"), snippets = 1) } +
            card("7", links = listOf("1", "2"), snippets = 1, isHub = true)
        val step = LumenCoach.nextStep(cards)
        assertTrue(step!!.contains("hub", ignoreCase = true))
        assertTrue(step.contains("Register"))
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

class LumenStructureMapTest {

    private fun card(
        id: String,
        indexNumber: String,
        box: Int = 1,
        front: String = "Note $indexNumber",
        back: String = "",
        links: List<String> = emptyList()
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        return LumenCardEntity(
            id = id,
            bookId = "",
            box = box,
            indexNumber = indexNumber,
            front = front,
            back = back,
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = LumenCapture.linksToJson(links),
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `clusters expand linked roots into their whole lines in shelf order`() {
        val hub = card("hub", "10", front = "Efficient shapes", links = listOf("c2", "c1"))
        val c1 = card("c1", "21", front = "Bees build hexagons")
        val c1a = card("c1a", "21a", front = "Hexagons pack tightly")
        val c2 = card("c2", "30", front = "Spheres minimize area")
        val c2a = card("c2a", "30a", front = "Soap bubbles")
        val c2a1 = card("c2a1", "30a1", front = "Triple junctions")
        val unrelated = card("u", "5", front = "Unrelated note")

        val clusters = LumenStructureMap.clusters(
            hub,
            listOf(hub, unrelated, c2a, c1a, c1, c2a1, c2)
        )

        // Roots in shelf order (21 before 30), regardless of link order.
        assertEquals(listOf("21", "30"), clusters.map { it.root.indexNumber })
        assertEquals(listOf("21", "21a"), clusters[0].steps.map { it.card.indexNumber })
        assertEquals(listOf("30", "30a", "30a1"), clusters[1].steps.map { it.card.indexNumber })
        // Unlinked cards are not part of the map, even though they share the box.
        assertTrue(clusters.none { it.root.id == "u" })
    }

    @Test
    fun `render lists the hub heading then each cluster indented under it`() {
        val hub = card("hub", "10", front = "Efficient shapes", links = listOf("c1"))
        val c1 = card("c1", "21", front = "Bees build hexagons")
        val c1a = card("c1a", "21a", front = "Hexagons pack tightly")
        val text = LumenStructureMap.render(
            hub,
            LumenStructureMap.clusters(hub, listOf(hub, c1, c1a))
        )
        assertTrue(text.contains("10  Efficient shapes"))
        assertTrue(text.contains("  21  Bees build hexagons"))
        assertTrue(text.contains("    21a  Hexagons pack tightly"))
    }

    @Test
    fun `a hub with no links has no clusters`() {
        val hub = card("hub", "10", front = "Empty hub")
        val other = card("c1", "21")
        assertTrue(LumenStructureMap.clusters(hub, listOf(hub, other)).isEmpty())
        assertEquals("10  Empty hub", LumenStructureMap.render(hub, emptyList()))
    }

    @Test
    fun `clusters from a hub in one box can point into another box`() {
        val hub = card("hub", "10", box = 1, front = "Cross-box hub", links = listOf("c2"))
        val c2 = card("c2", "1", box = 2, front = "Other line")
        val clusters = LumenStructureMap.clusters(hub, listOf(hub, c2))
        assertEquals(listOf("1"), clusters.map { it.root.indexNumber })
    }
}
