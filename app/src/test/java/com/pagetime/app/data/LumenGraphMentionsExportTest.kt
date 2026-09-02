package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenGraphTest {
    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $indexNumber",
        links: List<String> = emptyList(),
        isHub: Boolean = false,
    ): LumenCardEntity =
        LumenCardEntity(
            id = id,
            bookId = "",
            box = 1,
            indexNumber = indexNumber,
            front = front,
            back = "",
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = LumenCapture.linksToJson(links),
            isHub = isHub,
            createdAt = 0,
            updatedAt = 0,
        )

    @Test
    fun `every slip is a node and links become one undirected edge`() {
        val (nodes, edges) =
            LumenGraph.build(
                listOf(
                    card("a", "1", links = listOf("b")),
                    card("b", "2"),
                    card("c", "3"),
                ),
            )
        assertEquals(3, nodes.size)
        val links = edges.filter { it.kind == LumenGraphEdgeKind.LINK }
        assertEquals(1, links.size)
        assertEquals(setOf("a", "b"), setOf(links[0].fromId, links[0].toId))
    }

    @Test
    fun `filing hierarchy becomes parent edges`() {
        val (_, edges) =
            LumenGraph.build(
                listOf(
                    card("one", "1"),
                    card("a", "1a"),
                    card("a1", "1a1"),
                    // 210 is a sibling of 21, never its child.
                    card("twentyone", "21"),
                    card("twoten", "210"),
                ),
            )
        val parents = edges.filter { it.kind == LumenGraphEdgeKind.PARENT }
        assertEquals(setOf(setOf("one", "a"), setOf("a", "a1")), parents.map { setOf(it.fromId, it.toId) }.toSet())
        // 210 is not a parent edge of 21 — numbers are not decimals.
        assertTrue(parents.none { setOf(it.fromId, it.toId) == setOf("twentyone", "twoten") })
    }

    @Test
    fun `links to missing cards and self-links are dropped`() {
        val (_, edges) =
            LumenGraph.build(
                listOf(
                    card("a", "1", links = listOf("ghost", "a")),
                    card("b", "2"),
                ),
            )
        assertTrue(edges.filter { it.kind == LumenGraphEdgeKind.LINK }.isEmpty())
    }

    @Test
    fun `layout is deterministic and stays on canvas`() {
        val cards =
            listOf(
                card("a", "1", links = listOf("b", "c", "d")),
                card("b", "1a", links = listOf("c")),
                card("c", "2"),
                card("d", "3"),
                card("e", "4"),
            )
        val (nodes, edges) = LumenGraph.build(cards)
        val first = LumenGraphLayout.run(nodes, edges)
        val second = LumenGraphLayout.run(nodes, edges)
        assertEquals(first.positions, second.positions)
        assertEquals(5, first.shownCount)
        assertEquals(5, first.totalCount)
        first.positions.values.forEach { (x, y) ->
            assertTrue(x in 0f..1f)
            assertTrue(y in 0f..1f)
        }
    }

    @Test
    fun `huge boxes are capped with hubs kept first`() {
        val many = (1..400).map { i -> card("c$i", "$i", isHub = i == 7) }
        val (nodes, edges) = LumenGraph.build(many)
        val result = LumenGraphLayout.run(nodes, edges, maxNodes = 250)
        assertEquals(250, result.shownCount)
        assertEquals(400, result.totalCount)
        assertTrue(result.positions.containsKey("c7"))
    }
}

class LumenMentionsTest {
    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $indexNumber",
        back: String = "",
        quote: String = "",
        links: List<String> = emptyList(),
        updatedAt: Long = 0,
    ): LumenCardEntity =
        LumenCardEntity(
            id = id,
            bookId = "",
            box = 1,
            indexNumber = indexNumber,
            front = front,
            back = back,
            quote = quote,
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = LumenCapture.linksToJson(links),
            createdAt = 0,
            updatedAt = updatedAt,
        )

    @Test
    fun `a whole-title echo inside another slip is suggested`() {
        val bees = card("bees", "2", front = "Bees build hexagons")
        val shapes = card("shapes", "1", front = "Efficient shapes", back = "Bees build hexagons to save wax.")
        val suggestions = LumenMentions.suggest(listOf(bees, shapes))
        assertEquals(1, suggestions.size)
        assertEquals("shapes", suggestions[0].source.id)
        assertEquals("bees", suggestions[0].target.id)
        assertEquals(1, suggestions[0].count)
    }

    @Test
    fun `already-linked pairs are never suggested`() {
        val bees = card("bees", "2", front = "Bees build hexagons")
        val shapes = card("shapes", "1", front = "Efficient shapes", back = "Bees build hexagons.", links = listOf("bees"))
        assertTrue(LumenMentions.suggest(listOf(bees, shapes)).isEmpty())
    }

    @Test
    fun `the link is suggested from the other direction too`() {
        val bees = card("bees", "2", front = "Bees build hexagons")
        val shapes = card("shapes", "1", front = "Efficient shapes")
        val beestext = card("bees2", "3", front = "Efficient shapes and hexagons")
        val suggestions = LumenMentions.suggest(listOf(bees, shapes, beestext))
        assertEquals(1, suggestions.size)
        assertEquals("bees2", suggestions[0].source.id)
        assertEquals("shapes", suggestions[0].target.id)
    }

    @Test
    fun `fragment matches and short titles are ignored`() {
        val hexagonality = card("hex", "1", front = "Hexagonality in nature")
        val hexagons = card("hexagons", "2", front = "Hexagons", back = "Hexagonality is not Hexagons.")
        // "Hexagonality" is not "Hexagons" (whole-word), and "Hexagons" would be too short anyway.
        assertTrue(LumenMentions.suggest(listOf(hexagonality, hexagons)).isEmpty())
    }

    @Test
    fun `address-shaped titles are not treated as mentions`() {
        val ref = card("ref", "21/2a7", front = "21/2a7 cross-ref")
        val text = card("text", "1", front = "See 21/2a7 for the original")
        assertTrue(LumenMentions.suggest(listOf(ref, text)).isEmpty())
    }

    @Test
    fun `multi-word titles match whole phrases`() {
        val concept = card("concept", "2", front = "Efficient shapes in architecture")
        val text = card("text", "1", back = "Efficient shapes in architecture wins every time.")
        val suggestions = LumenMentions.suggest(listOf(concept, text))
        assertEquals(1, suggestions.size)
        assertEquals("concept", suggestions[0].target.id)
    }
}

class LumenBoxExportTest {
    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $indexNumber",
        links: List<String> = emptyList(),
        isHub: Boolean = false,
        snippets: List<LumenSnippet> = emptyList(),
        dueAt: Long? = null,
    ): LumenCardEntity =
        LumenCardEntity(
            id = id,
            bookId = "book-1",
            box = 2,
            indexNumber = indexNumber,
            front = front,
            back = "Back of $front",
            quote = "A quoted passage",
            sourceLocatorJson = """{"href":"/x"}""",
            sourceChapterIndex = 3,
            sourceFraction = 0.5f,
            snippetsJson = LumenCapture.snippetsToJson(snippets),
            isHub = isHub,
            linksJson = LumenCapture.linksToJson(links),
            keywords = "hexagons efficiency",
            fsrsCardJson = """{"due":"2026-01-01"}""",
            dueAt = dueAt,
            reviewCount = 4,
            lastRating = 3,
            createdAt = 100,
            updatedAt = 200,
        )

    @Test
    fun `export round-trips every field`() {
        val original =
            listOf(
                card(
                    "a",
                    "21/2a7",
                    front = "Efficiency of hexagons",
                    links = listOf("b"),
                    isHub = true,
                    snippets = listOf(LumenSnippet("Later thought", 150, 0.75f)),
                    dueAt = 12345,
                ),
                card("b", "22"),
            )
        val restored = LumenBoxExport.fromJson(LumenBoxExport.toJson(original))!!
        assertEquals(2, restored.size)
        val a = restored.first { it.id == "a" }
        assertEquals("21/2a7", a.indexNumber)
        assertEquals("Efficiency of hexagons", a.front)
        assertEquals(listOf("b"), LumenCapture.linksFromJson(a.linksJson))
        assertTrue(a.isHub)
        assertEquals(1, LumenCapture.snippetsFromJson(a.snippetsJson).size)
        assertEquals(12345L, a.dueAt)
        assertEquals(4, a.reviewCount)
        assertEquals(3, a.lastRating)
        assertEquals("book-1", a.bookId)
        assertEquals(2, a.box)
        assertEquals(0.5f, a.sourceFraction, 0.001f)
        assertEquals(200, a.updatedAt)
    }

    @Test
    fun `unknown or foreign documents are rejected`() {
        assertTrue(LumenBoxExport.fromJson("""{"format":"other","cards":[]}""") == null)
        assertTrue(LumenBoxExport.fromJson("not json at all") == null)
    }

    @Test
    fun `empty box exports and restores to an empty list`() {
        val restored = LumenBoxExport.fromJson(LumenBoxExport.toJson(emptyList()))!!
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `export document carries format metadata`() {
        val json = LumenBoxExport.toJson(listOf(card("a", "1")))
        val root = org.json.JSONObject(json)
        assertEquals("pagetime-lumen-box", root.optString("format"))
        assertNotNull(root.optJSONArray("cards"))
    }
}
