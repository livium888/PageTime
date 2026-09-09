package com.pagetime.app.data.shelf

import com.pagetime.app.data.gutenberg.GutendexBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this guards against is silent: the reader taps Moby-Dick, gets
 * a different book, and nothing on screen says so. Every test here is a case
 * where taking the first search result would have done that.
 */
class ShelfMatcherTest {

    private fun book(
        id: Long,
        title: String,
        authors: List<String>,
        downloads: Long = 100,
        epub: String? = "https://example.test/e.epub",
        txt: String? = null,
    ) = GutendexBook(
        id = id,
        title = title,
        authors = authors,
        downloadCount = downloads,
        epubUrl = epub,
        txtUrl = txt,
        htmlUrl = null,
        coverUrl = null,
    )

    private fun entry(title: String, author: String, aliases: List<String> = emptyList()) =
        LadderEntry("k", title, author, LadderStage.NOVEL, "note", aliases)

    // --- Titles the catalogue spells differently ---

    @Test
    fun `a subtitle in the catalogue does not break the match`() {
        val m = ShelfMatcher.bestMatch(
            entry("Moby-Dick", "Herman Melville"),
            listOf(book(2701, "Moby Dick; Or, The Whale", listOf("Melville, Herman"))),
        )
        assertEquals(2701L, m?.id)
    }

    @Test
    fun `a leading article on either side is ignored`() {
        assertTrue(ShelfMatcher.titleAgrees("The Republic", "Republic of Plato"))
        assertTrue(ShelfMatcher.titleAgrees("The Iliad", "The Iliad of Homer"))
        assertTrue(ShelfMatcher.titleAgrees("The Metamorphosis", "Metamorphosis"))
    }

    @Test
    fun `a title must match at a word boundary`() {
        assertTrue(ShelfMatcher.titleAgrees("Ethics", "Ethics"))
        assertTrue(ShelfMatcher.titleAgrees("Ethics", "Ethics, Part I"))
        assertFalse(ShelfMatcher.titleAgrees("Ethics", "Ethicsology of Machines"))
    }

    // --- The author gate, which is the whole defence ---

    /**
     * Meditations is Marcus Aurelius and it is also Descartes. A title-only
     * match hands the reader the wrong one with complete confidence.
     */
    @Test
    fun `a shared title with the wrong author is refused`() {
        val m = ShelfMatcher.bestMatch(
            entry("Meditations", "Marcus Aurelius"),
            listOf(book(1, "Meditations on First Philosophy", listOf("Descartes, René"))),
        )
        assertNull(m)
    }

    @Test
    fun `The Prince is not The Prince and the Pauper`() {
        val m = ShelfMatcher.bestMatch(
            entry("The Prince", "Niccolò Machiavelli"),
            listOf(book(1, "The Prince and the Pauper", listOf("Twain, Mark"))),
        )
        assertNull(m)
    }

    @Test
    fun `an ancient with no surname still matches`() {
        assertTrue(ShelfMatcher.authorAgrees(entry("The Iliad", "Homer"), book(1, "The Iliad", listOf("Homer"))))
        assertTrue(ShelfMatcher.authorAgrees(entry("The Republic", "Plato"), book(1, "The Republic", listOf("Plato"))))
        assertTrue(
            ShelfMatcher.authorAgrees(
                entry("Meditations", "Marcus Aurelius"),
                book(1, "Meditations", listOf("Marcus Aurelius, Emperor of Rome"))
            )
        )
    }

    @Test
    fun `surname-first catalogue spelling matches a natural name`() {
        assertTrue(ShelfMatcher.authorAgrees(entry("x", "Jane Austen"), book(1, "x", listOf("Austen, Jane"))))
        assertTrue(
            ShelfMatcher.authorAgrees(
                entry("x", "Michel de Montaigne"),
                book(1, "x", listOf("Montaigne, Michel de"))
            )
        )
        assertTrue(
            ShelfMatcher.authorAgrees(
                entry("x", "Jean-Jacques Rousseau"),
                book(1, "x", listOf("Rousseau, Jean-Jacques"))
            )
        )
    }

    /**
     * The transliteration case, which is a false NEGATIVE and therefore the
     * quiet one: nothing looks broken, two of the best novels on the ladder
     * simply say they cannot be found.
     */
    @Test
    fun `a differently transliterated surname needs an alias to match`() {
        val plain = entry("Crime and Punishment", "Fyodor Dostoevsky")
        val listed = book(2554, "Crime and Punishment", listOf("Dostoyevsky, Fyodor"))
        assertFalse("without the alias this silently fails", ShelfMatcher.authorAgrees(plain, listed))

        val aliased = entry("Crime and Punishment", "Fyodor Dostoevsky", listOf("Dostoyevsky"))
        assertTrue(ShelfMatcher.authorAgrees(aliased, listed))
        assertEquals(2554L, ShelfMatcher.bestMatch(aliased, listOf(listed))?.id)
    }

    /** An alias must not become a back door round the author gate. */
    @Test
    fun `an alias still has to be one of the listed authors`() {
        val aliased = entry("Crime and Punishment", "Fyodor Dostoevsky", listOf("Dostoyevsky"))
        assertFalse(
            ShelfMatcher.authorAgrees(aliased, book(1, "Crime and Punishment", listOf("Tolstoy, Leo")))
        )
    }

    /**
     * The ladder's own entries, checked against the spellings the catalogues
     * are known to use. These are the ones that would fail silently.
     */
    @Test
    fun `the ladder carries the aliases its catalogue spellings need`() {
        fun ladder(key: String) = ReadingLadders.greatBooks.first { it.key == key }
        assertTrue(
            ShelfMatcher.authorAgrees(
                ladder("crime-punishment"),
                book(2554, "Crime and Punishment", listOf("Dostoyevsky, Fyodor"))
            )
        )
        assertTrue(
            ShelfMatcher.authorAgrees(
                ladder("karamazov"),
                book(28054, "The Brothers Karamazov", listOf("Dostoyevsky, Fyodor"))
            )
        )
        assertTrue(
            ShelfMatcher.authorAgrees(ladder("aeneid"), book(228, "The Aeneid", listOf("Virgil")))
        )
        assertTrue(
            ShelfMatcher.authorAgrees(ladder("aeneid"), book(228, "The Aeneid", listOf("Vergil")))
        )
    }

    @Test
    fun `an entry with no author on the catalogue side cannot match`() {
        assertFalse(ShelfMatcher.authorAgrees(entry("The Iliad", "Homer"), book(1, "The Iliad", emptyList())))
    }

    // --- Nothing to fetch is not a match ---

    @Test
    fun `a result with no downloadable file is skipped`() {
        val m = ShelfMatcher.bestMatch(
            entry("Moby-Dick", "Herman Melville"),
            listOf(book(2701, "Moby Dick", listOf("Melville, Herman"), epub = null, txt = null)),
        )
        assertNull(m)
    }

    @Test
    fun `a plain text file is enough`() {
        val m = ShelfMatcher.bestMatch(
            entry("Moby-Dick", "Herman Melville"),
            listOf(book(2701, "Moby Dick", listOf("Melville, Herman"), epub = null, txt = "t.txt")),
        )
        assertEquals(2701L, m?.id)
    }

    // --- Choosing between real candidates ---

    @Test
    fun `the most downloaded edition wins`() {
        val m = ShelfMatcher.bestMatch(
            entry("The Iliad", "Homer"),
            listOf(
                book(1, "The Iliad of Homer, Volume 2", listOf("Homer"), downloads = 12),
                book(2, "The Iliad", listOf("Homer"), downloads = 9_000),
                book(3, "The Iliad of Homer", listOf("Homer"), downloads = 300),
            ),
        )
        assertEquals(2L, m?.id)
    }

    @Test
    fun `nothing matching returns nothing rather than the nearest thing`() {
        val m = ShelfMatcher.bestMatch(
            entry("Middlemarch", "George Eliot"),
            listOf(
                book(1, "Adam Bede", listOf("Eliot, George")),
                book(2, "Middlemarch", listOf("Someone Else")),
            ),
        )
        assertNull(m)
    }

    @Test
    fun `an empty catalogue answer is not a match`() {
        assertNull(ShelfMatcher.bestMatch(entry("Middlemarch", "George Eliot"), emptyList()))
    }

    @Test
    fun `normalisation folds punctuation and case`() {
        assertEquals("moby dick or the whale", ShelfMatcher.normalize("Moby-Dick; Or, The Whale"))
        assertEquals("montaigne", ShelfMatcher.surnameKey("Michel de Montaigne"))
        assertEquals("homer", ShelfMatcher.surnameKey("Homer"))
    }

    /**
     * Every ladder entry has to be askable: a blank author key would match
     * every book in the catalogue.
     */
    @Test
    fun `every ladder entry yields a usable author key`() {
        ReadingLadders.greatBooks.forEach {
            val key = ShelfMatcher.surnameKey(it.author)
            assertTrue("no author key for ${it.key} (${it.author})", !key.isNullOrBlank())
        }
    }
}
