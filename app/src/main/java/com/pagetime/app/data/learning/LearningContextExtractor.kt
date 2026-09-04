package com.pagetime.app.data.learning

import android.content.Context
import android.net.Uri
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCheckpoint
import com.pagetime.app.data.library.EpubParser
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Builds bounded, source-grounded text windows for learning requests. */
class LearningContextExtractor(
    private val context: Context,
    private val epubParser: EpubParser
) {
    suspend fun extract(book: BookEntity, chapterIndex: Int, maxCharacters: Int = 40_000): LearningContext =
        extract(
            book,
            chapterIndex,
            checkpoint = null,
            currentLocatorJson = null,
            currentTextOffset = null,
            maxCharacters = maxCharacters
        )

    /**
     * Uses the checkpoint as a content boundary. No checkpoint means chapter start.
     * This method never navigates the reader; it only changes the text sent to AI.
     */
    suspend fun extract(
        book: BookEntity,
        chapterIndex: Int,
        checkpoint: LearningCheckpoint?,
        currentLocatorJson: String?,
        currentTextOffset: Int?,
        maxCharacters: Int = 40_000
    ): LearningContext {
        require(maxCharacters in 2_000..80_000)
        // Extraction unzips the book, parses chapter HTML and reads whole text
        // files. That is blocking work, so it is confined to the IO dispatcher
        // here rather than left to each caller to remember — running it on the
        // main dispatcher freezes the reader until the parse finishes.
        return withContext(Dispatchers.IO) {
            if (book.format == "epub") {
                extractEpub(book, chapterIndex, checkpoint, currentLocatorJson, maxCharacters)
            } else {
                extractText(book, chapterIndex, checkpoint, currentTextOffset, maxCharacters)
            }
        }
    }

    private fun extractText(
        book: BookEntity,
        chapterIndex: Int,
        checkpoint: LearningCheckpoint?,
        currentTextOffset: Int?,
        maxCharacters: Int
    ): LearningContext {
        val full = File(book.localPath).readText()
        val start = checkpoint?.textOffset?.coerceIn(0, full.length) ?: 0
        val end = (currentTextOffset ?: full.length).coerceIn(start, full.length)
        val bounded = full.substring(start, end).take(maxCharacters)
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = chapterIndex,
            chapterTitle = "Reading window",
            recentText = bounded,
            sourceFormat = "txt"
        )
    }

    private fun extractEpub(
        book: BookEntity,
        chapterIndex: Int,
        checkpoint: LearningCheckpoint?,
        currentLocatorJson: String?,
        maxCharacters: Int
    ): LearningContext {
        val extracted = File(context.cacheDir, "epub/${book.id}")
        val parsed = try {
            epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return extractEpubRaw(book, maxCharacters)
        }
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        val chapter = parsed.chapters[active]
        val raw = chapterRawText(book, chapter.filePath, chapter.title)
        val text = run {
            val startFraction = checkpointFraction(checkpoint, chapter.filePath)
            val endFraction = currentFraction(currentLocatorJson, chapter.filePath)
            val start = (raw.length * startFraction).toInt().coerceIn(0, raw.length)
            val end = (raw.length * endFraction.coerceAtLeast(startFraction)).toInt().coerceIn(start, raw.length)
            raw.substring(start, end).ifBlank { raw }
        }
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = active,
            chapterTitle = chapter.title,
            recentText = text.take(maxCharacters),
            sourceFormat = "epub"
        )
    }

    /**
     * A bounded passage CENTERED on the current reading location in the active
     * chapter, for Lumen capture. Unlike [extract] (which grows from the chapter
     * start and is capped by [maxCharacters]), this never freezes: as the locator
     * advances within the chapter, the returned window slides forward with it, so
     * capturing on two different pages yields two different source passages.
     */
    suspend fun captureEpub(
        book: BookEntity,
        chapterIndex: Int,
        currentLocatorJson: String?,
        progressionOverride: Float? = null,
        anchorText: String? = null
    ): String = withContext(Dispatchers.IO) {
        captureEpubBlocking(book, chapterIndex, currentLocatorJson, progressionOverride, anchorText)
    }

    /**
     * The blocking body of [captureEpub]: unzips the book and parses chapter
     * HTML, so it is only ever called from the IO dispatcher above.
     */
    private fun captureEpubBlocking(
        book: BookEntity,
        chapterIndex: Int,
        currentLocatorJson: String?,
        progressionOverride: Float?,
        anchorText: String?
    ): String {
        val extracted = File(context.cacheDir, "epub/${book.id}")
        val parsed = try {
            epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ""
        }
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        val chapter = parsed.chapters.getOrNull(active) ?: return ""
        val raw = runCatching {
            chapterRawText(book, chapter.filePath, chapter.title)
        }.getOrElse { return "" }
        if (raw.isBlank()) return ""
        // Where the passage ENDS. Text the reader pointed at is exact and is
        // preferred: found in the chapter, it names the paragraph they just
        // read. Everything else is an estimate of where they are — the
        // locator's fraction is measured against Readium's own rendering, not
        // against this text, so it lands near the right paragraph rather than
        // on it.
        val anchor = anchorText?.trim()?.takeIf { it.length >= MIN_ANCHOR_CHARS }
            ?.let { needle -> raw.indexOf(needle).takeIf { it >= 0 }?.plus(needle.length) }
            ?: run {
                val fraction = currentLocatorJson
                    ?.let { locatorFraction(it, chapter.filePath) }
                    ?: progressionOverride
                    ?: 1f
                (raw.length * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, raw.length)
            }
        return LumenCapture.paragraphPassage(raw, anchor)
    }

    /**
     * A chapter as paragraphs, blank-line separated, with the title first.
     *
     * This used to be Jsoup's `.text()`, which flattens a whole document into
     * one whitespace-normalised string. Every paragraph boundary in the book
     * was destroyed on the line before the only code that could have used it —
     * which is why a capture could only measure in characters and reached back
     * an arbitrary distance into whatever happened to be there.
     *
     * The structure was never missing. An EPUB chapter is XHTML with real <p>
     * elements and Jsoup has already built the DOM; the text just had to be
     * read out of it a block at a time.
     *
     * Only leaf blocks are taken. A <blockquote> wrapping a <p> would otherwise
     * contribute its text twice — once as itself and once as its child.
     */
    private fun chapterRawText(book: BookEntity, filePath: String, title: String): String =
        ZipFile(book.localPath).use { zip ->
            val entry = zip.getEntry(filePath) ?: zip.getEntry(filePath.substringBefore('#'))
            val plain = entry?.let { item ->
                runCatching {
                    zip.getInputStream(item).use { input ->
                        paragraphsOf(Jsoup.parse(input, Charsets.UTF_8.name(), ""))
                    }
                }.getOrNull().orEmpty()
            }.orEmpty()
            listOf(title, plain).filter { it.isNotBlank() }
                .joinToString(LumenCapture.PARAGRAPH_BREAK)
        }

    private fun paragraphsOf(document: org.jsoup.nodes.Document): String {
        val body = document.body() ?: return document.text()
        val paragraphs = body.select(BLOCK_SELECTOR)
            // toList() is load-bearing. Elements has its own member filter(),
            // taking a Jsoup NodeFilter, and in Kotlin a member always wins
            // over an extension — so filtering an Elements directly binds to
            // Jsoup's node-walking API rather than the stdlib. A plain List has
            // no such member and resolves the way it reads.
            .toList()
            // Leaf blocks only: a block containing another block is a container,
            // and its text belongs to the children.
            .filter { it.select(BLOCK_SELECTOR).isEmpty() }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        // A chapter with no block markup at all is still a chapter. Falling back
        // to the flat text keeps it capturable, as one long paragraph.
        return if (paragraphs.isEmpty()) body.text()
        else paragraphs.joinToString(LumenCapture.PARAGRAPH_BREAK)
    }

    private fun checkpointFraction(checkpoint: LearningCheckpoint?, chapterPath: String): Float =
        checkpoint?.locatorJson
            ?.let { locatorFraction(it, chapterPath) }
            ?.coerceIn(0f, 1f)
            ?: 0f

    private fun currentFraction(locatorJson: String?, chapterPath: String): Float =
        locatorJson
            ?.let { locatorFraction(it, chapterPath) }
            ?.coerceIn(0f, 1f)
            ?: 1f

    /**
     * Readium locator hrefs are decoded while EPUB OPF hrefs are usually
     * percent-encoded ("Chapter%201.xhtml" vs "Chapter 1.xhtml"), so a raw
     * string contains() check fails for most books and silently expands the
     * learning range to the whole chapter. Compare aggressively-normalized
     * resource keys instead, and only trust the progression when the locator
     * really points at this chapter.
     */
    private fun locatorFraction(locatorJson: String, chapterPath: String): Float? {
        val href = locatorHref(locatorJson) ?: return null
        val chapterKey = resourceKey(chapterPath)
        if (chapterKey.isBlank()) return null
        val hrefKey = resourceKey(href)
        val matches = hrefKey == chapterKey ||
            hrefKey.endsWith(chapterKey) ||
            chapterKey.endsWith(hrefKey)
        if (!matches) return null
        return Regex("\\\"progression\\\"\\s*:\\s*([0-9.]+)")
            .find(locatorJson)
            ?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
    }

    /** First href value in the locator JSON, with escaped solidi unescaped. */
    private fun locatorHref(locatorJson: String): String? =
        Regex("\\\"href\\\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(locatorJson)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")

    /**
     * Decodes, strips fragments, and normalizes separators to "|" (kept, so
     * suffix matching cannot confuse "intro.xhtml" with "myintro.xhtml").
     * Both percent-encoded and decoded href forms produce the same key.
     */
    private fun resourceKey(value: String): String =
        "|" + Uri.decode(value)
            .substringBefore('#')
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "|")
            .trim('|') + "|"

    private fun extractEpubRaw(book: BookEntity, maxCharacters: Int): LearningContext {
        val content = ZipFile(book.localPath).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && (
                        entry.name.endsWith(".xhtml", ignoreCase = true) ||
                            entry.name.endsWith(".html", ignoreCase = true) ||
                            entry.name.endsWith(".htm", ignoreCase = true)
                        )
                }
                .filter { entry ->
                    val lower = entry.name.lowercase()
                    !(lower.contains("nav") || lower.contains("toc") || lower.contains("cover") || lower.contains("titlepage"))
                }
                .sortedBy { it.name }
                .toList()
                .takeLast(3)
                .joinToString("\n\n") { entry ->
                    runCatching {
                        zip.getInputStream(entry).use { input -> Jsoup.parse(input, Charsets.UTF_8.name(), "").text() }
                    }.getOrNull().orEmpty()
                }
        }
        return LearningContext(book.id, book.title, 0, "Reading window", content.takeLast(maxCharacters), "epub")
    }

    private companion object {
        /**
         * Shortest selection worth searching the chapter for. A word or two
         * appears all over a chapter, so the first match would as often as not
         * be somewhere the reader never was.
         */
        const val MIN_ANCHOR_CHARS = 12

        /**
         * Elements that end a paragraph when a reader looks at the page. Chosen
         * to match what reads as a break, not what the spec calls a block: a
         * <div> is excluded because books use it for chapter and section
         * wrappers far more often than for prose.
         */
        /**
         * What counts as a block of prose.
         *
         * div is in the list, and has to be. Plenty of commercial EPUBs mark
         * every paragraph with a styled <div> and never emit a <p> at all —
         * and for those books this selector matched nothing, paragraphsOf fell
         * back to flat text, and a whole 99,000-character chapter came back as
         * a single "paragraph". The capture then had nothing to cut on and
         * handed the trimmer the entire chapter.
         *
         * Nesting is not a problem: the leaf-block filter drops any block that
         * contains another, so a wrapper div full of paragraphs is excluded and
         * its children are kept. Only a div with no block inside it — which is
         * exactly the div-as-paragraph case — survives.
         */
        const val BLOCK_SELECTOR =
            "p, div, h1, h2, h3, h4, h5, h6, li, blockquote, dd, dt, figcaption, pre"
    }
}
