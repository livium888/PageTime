package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenTreeTest {
    private fun card(
        id: String,
        indexNumber: String,
        front: String = "Note $indexNumber",
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
            linksJson = "[]",
            createdAt = 0,
            updatedAt = 0,
        )

    private fun addresses(nodes: List<LumenTree.Node>): List<String> = nodes.map { it.card.indexNumber }

    @Test
    fun `trunk lines branch into trees exactly like the original box`() {
        val roots =
            LumenTree.build(
                listOf(
                    card("b", "1b", "Sibling branch"),
                    card("a", "1a", "First branch"),
                    card("a1", "1a1", "Grandchild"),
                    card("a1a", "1a1a", "Great-grandchild"),
                    card("one", "1", "Einheit und Vereinheitlichung der Gruppe"),
                    card("two", "2", "Main line two"),
                    card("21", "21", "Literatur"),
                    card("21a", "21a", "Child of Literatur"),
                    card("210", "210", "Wissenschaft — a sibling of 21, not a child"),
                ),
            )

        // Trunks in shelf order: 1, 2, 21, 210 (210 is NOT under 21).
        assertEquals(listOf("1", "2", "21", "210"), addresses(roots))

        val one = roots[0]
        assertEquals(listOf("1a", "1b"), addresses(one.children))
        assertEquals(listOf("1a1"), addresses(one.children[0].children))
        assertEquals(listOf("1a1a"), addresses(one.children[0].children[0].children))
        // Everything under 1, itself excluded.
        assertEquals(4, one.descendantCount)
        assertEquals(0, one.children[1].descendantCount)

        val literatur = roots[2]
        assertEquals(listOf("21a"), addresses(literatur.children))
        assertEquals(1, literatur.descendantCount)
        assertEquals(0, roots[3].descendantCount)
    }

    @Test
    fun `blank and missing addresses are omitted`() {
        val roots = LumenTree.build(listOf(card("x", "", "No address"), card("one", "1"), card("a", "1a")))
        assertEquals(listOf("1"), addresses(roots))
        assertEquals(listOf("1a"), addresses(roots[0].children))
    }

    @Test
    fun `empty box has an empty map`() {
        assertTrue(LumenTree.build(emptyList()).isEmpty())
    }

    @Test
    fun `a branch whose middle slip was deleted hangs under the surviving ancestor`() {
        // 1a exists only implicitly (deleted); 1a1 still files under 1.
        val roots = LumenTree.build(listOf(card("1", "1"), card("1a1", "1a1")))
        assertEquals(listOf("1"), addresses(roots))
        assertEquals(listOf("1a1"), addresses(roots[0].children))
        assertEquals(1, roots[0].descendantCount)
    }
}

class LumenReferencesTest {
    private fun card(
        id: String,
        indexNumber: String,
    ): LumenCardEntity =
        LumenCardEntity(
            id = id,
            bookId = "",
            box = 1,
            indexNumber = indexNumber,
            front = "Note $indexNumber",
            back = "",
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = "[]",
            createdAt = 0,
            updatedAt = 0,
        )

    private val box =
        listOf(
            card("c-21-2", "21/2"),
            card("c-21", "21"),
            card("c-1a", "1a"),
            card("c-134g1", "13,4g1"),
            card("c-571", "57,1"),
        )

    @Test
    fun `slash cross-references resolve to the written slip`() {
        val mentions = LumenReferences.find("Vgl. grundsätzlich 21/2 und dazu 21.", box)
        assertEquals(listOf("21/2", "21"), mentions.map { it.address })
    }

    @Test
    fun `comma sub-numbers resolve like Luhmann wrote them`() {
        val mentions = LumenReferences.find("systematische Anknüpfung 13,4g1; Horizont 57,1", box)
        assertEquals(listOf("13,4g1", "57,1"), mentions.map { it.address })
        assertEquals(listOf("c-134g1", "c-571"), mentions.map { it.cardId })
    }

    @Test
    fun `matching is case-insensitive`() {
        val mentions = LumenReferences.find("Siehe 1A für Einheit und 21/2.", box)
        assertEquals(listOf("1a", "21/2"), mentions.map { it.address })
    }

    @Test
    fun `a token never links to a shorter slip with the same prefix`() {
        // Only 1a exists in this text's box — 1a1 is not a valid target and
        // must not degrade to a link to 1a.
        val local = listOf(card("c-1a", "1a"))
        assertTrue(LumenReferences.find("genauer dazu 1a1", local).isEmpty())
    }

    @Test
    fun `mentions keep their spans so text can be re-rendered`() {
        val text = "Siehe 21/2 für Details"
        val mentions = LumenReferences.find(text, box)
        assertEquals(1, mentions.size)
        val m = mentions[0]
        assertEquals("21/2", text.substring(m.start, m.end))
    }

    @Test
    fun `unknown addresses and plain prose are not linked`() {
        assertTrue(LumenReferences.find("Es gibt keine Treffer hier.", box).isEmpty())
        assertTrue(LumenReferences.find("Vgl. 99/99a und 13,9x", box).isEmpty())
    }
}
