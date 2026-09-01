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
import org.jsoup.Jsoup

/** Builds bounded, source-grounded text windows for learning requests. */
class LearningContextExtractor(
    private val context: Context,
    private val epubParser: EpubParser
) {
    fun extract(book: BookEntity, chapterIndex: Int, maxCharacters: Int = 40_000): LearningContext =
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
    fun extract(
        book: BookEntity,
        chapterIndex: Int,
        checkpoint: LearningCheckpoint?,
        currentLocatorJson: String?,
        currentTextOffset: Int?,
        maxCharacters: Int = 40_000
    ): LearningContext {
        require(maxCharacters in 2_000..80_000)
        return if (book.format == "epub") {
            extractEpub(book, chapterIndex, checkpoint, currentLocatorJson, maxCharacters)
        } else {
            extractText(book, chapterIndex, checkpoint, currentTextOffset, maxCharacters)
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
    fun captureEpub(
        book: BookEntity,
        chapterIndex: Int,
        currentLocatorJson: String?,
        progressionOverride: Float? = null,
        radiusChars: Int = LumenCapture.DEFAULT_RADIUS_CHARS
    ): String {
        val extracted = File(context.cacheDir, "epub/${book.id}")
        val parsed = try {
            epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ""
        }
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        val chapter = parsed.chapters[active]
        val raw = chapterRawText(book, chapter.filePath, chapter.title)
        if (raw.isBlank()) return ""
        // Center on the current position. Prefer the locator JSON (it carries the
        // resource href so the fraction is only trusted for THIS chapter), then
        // the caller's direct progression (some locators serialize without it),
        // then the end of chapter as a last resort.
        val fraction = currentLocatorJson
            ?.let { locatorFraction(it, chapter.filePath) }
            ?: progressionOverride
            ?: 1f
        val center = (raw.length * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, raw.length)
        return LumenCapture.captureWindow(raw, center, radiusChars)
    }

    /** Full plain text of a chapter: "<title>\n<body>". */
    private fun chapterRawText(book: BookEntity, filePath: String, title: String): String =
        ZipFile(book.localPath).use { zip ->
            val entry = zip.getEntry(filePath) ?: zip.getEntry(filePath.substringBefore('#'))
            val plain = entry?.let { item ->
                runCatching {
                    zip.getInputStream(item).use { input ->
                        Jsoup.parse(input, Charsets.UTF_8.name(), "").text()
                    }
                }.getOrNull().orEmpty()
            }.orEmpty()
            "$title\n$plain"
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
}
