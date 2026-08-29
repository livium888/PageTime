package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LumenAddressTest {

    @Test
    fun `first card in an empty box gets address 1`() {
        assertEquals("1", LumenAddress.nextAddress(emptyList(), null))
    }

    @Test
    fun `cards append at the top level`() {
        val existing = listOf("1", "2", "3")
        assertEquals("4", LumenAddress.nextAddress(existing, null))
    }

    @Test
    fun `gaps in numbering are not reused`() {
        val existing = listOf("1", "3")
        assertEquals("4", LumenAddress.nextAddress(existing, null))
    }

    @Test
    fun `branching extends with a letter`() {
        val existing = listOf("1", "2", "21")
        assertEquals("21a", LumenAddress.nextAddress(existing, "21"))
    }

    @Test
    fun `branching past 26 uses repeated letters like Luhmann`() {
        // 21a through 21z are all taken; the next branch must be 21aa.
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        val taken = (0 until 26).map { "21${alphabet[it]}" }
        assertEquals("21aa", LumenAddress.nextAddress(taken, "21"))
    }

    @Test
    fun `nested branch of a branch`() {
        val existing = listOf("21", "21a", "21aa")
        assertEquals("21aaa", LumenAddress.nextAddress(existing, "21aa"))
    }

    @Test
    fun `relative part strips the box prefix`() {
        assertEquals("2a7", LumenAddress.relativePart("21/2a7"))
        assertEquals("3", LumenAddress.relativePart("3"))
    }

    @Test
    fun `natural sort orders numbers before lettered branches`() {
        val sorted = listOf("21/3", "21/2a", "21/2", "21/2b", "21/10", "21/1")
            .sortedWith(LumenAddress.COMPARATOR)
        assertEquals(
            listOf("21/1", "21/2", "21/2a", "21/2b", "21/3", "21/10"),
            sorted
        )
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
