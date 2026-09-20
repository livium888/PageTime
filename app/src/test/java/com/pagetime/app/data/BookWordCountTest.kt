package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookWordCountTest {

    @Test
    fun `empty text has no words`() {
        assertEquals(0, BookWordCount.count(""))
    }

    @Test
    fun `blank text has no words`() {
        assertEquals(0, BookWordCount.count("   \n\t  "))
    }

    @Test
    fun `counts words separated by single spaces`() {
        assertEquals(4, BookWordCount.count("it was a dream"))
    }

    @Test
    fun `collapses runs of whitespace, including newlines and tabs`() {
        assertEquals(3, BookWordCount.count("one\n\ntwo\t\tthree"))
    }

    @Test
    fun `ignores leading and trailing whitespace`() {
        assertEquals(2, BookWordCount.count("  two words  "))
    }
}
