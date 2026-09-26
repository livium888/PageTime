package com.pagetime.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFlashcardTextCleanerTest {

    @Test
    fun `repeated headers and page numbers are removed before card generation`() {
        val pages = (1..4).map { page ->
            """THE HISTORY OF IDEAS
                CHAPTER 4
                The institutions changed as local pressures met and leaders responded. Example $page illustrates the wider social consequences.
                Each account adds a different cause to the larger change, while the next generation inherits the result.
                The argument continues through later political conflicts and economic changes.
                Page footer $page
                $page
            """.trimIndent()
        }

        val cleaned = PdfTextCleaner.cleanForFlashcards(pages, 2)

        assertTrue("cleaned page was: $cleaned", cleaned.contains("The institutions changed"))
        assertTrue(!cleaned.contains("THE HISTORY OF IDEAS"))
        assertTrue(!cleaned.contains("CHAPTER 4"))
        assertTrue(!cleaned.contains("Page footer"))
        assertTrue(!Regex("(?m)^\\s*3\\s*$").containsMatchIn(cleaned))
    }

    @Test
    fun `explicit endnotes section and pages after it are not sent for cards`() {
        val pages = listOf(
            "The chapter explains how the policy changed trade.",
            """Endnotes

                1. Smith, A. The history of trade, 1998.
            """.trimIndent(),
            "2. Jones, B. Markets and states, 2004.",
        )

        assertEquals("", PdfTextCleaner.cleanForFlashcards(pages, 1))
        assertEquals("", PdfTextCleaner.cleanForFlashcards(pages, 2))
        assertEquals(
            "The chapter explains how the policy changed trade.",
            PdfTextCleaner.cleanForFlashcards(pages, 0),
        )
    }

    @Test
    fun `copyright and obvious doi reference paragraphs are excluded`() {
        val source = """A policy succeeds only when people have a reason to follow it.

            Copyright 2024 Example Press. All rights reserved.

            [12] Smith, A. Journal of History. DOI: 10.1234/example
        """.trimIndent()

        assertEquals(
            "A policy succeeds only when people have a reason to follow it.",
            PdfTextCleaner.cleanForFlashcards(source),
        )
    }

    @Test
    fun `ordinary text mentioning a copyright concept is retained`() {
        val source = "Copyright rules reward authors, but they do not guarantee that a book will be read."
        assertEquals(source, PdfTextCleaner.cleanForFlashcards(source))
    }
}
