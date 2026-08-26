package com.pagetime.app.data.learning

import android.content.Context
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.library.EpubParser
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

/**
 * Builds the only text window Gemini is allowed to see. The active chapter plus the
 * prior two chapters gives enough continuity to identify topics without uploading a
 * whole book or unrelated earlier content.
 *
 * The window is generous (16k characters) because each chapter is processed by the
 * AI at most once and the result is cached locally; a wider window buys better
 * cards and a richer concept map without multiplying API calls.
 */
class LearningContextExtractor(
    private val context: Context,
    private val epubParser: EpubParser
) {
    fun extract(book: BookEntity, chapterIndex: Int, maxCharacters: Int = 16_000): LearningContext {
        require(maxCharacters in 2_000..40_000)
        return if (book.format == "epub") {
            extractEpub(book, chapterIndex, maxCharacters)
        } else {
            extractText(book, chapterIndex, maxCharacters)
        }
    }

    private fun extractText(book: BookEntity, chapterIndex: Int, maxCharacters: Int): LearningContext {
        val full = File(book.localPath).readText()
        val paragraphs = full.split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter { it.isNotBlank() }
        // Plain-text downloads do not carry reliable chapter metadata. Treat the
        // book as five stable reading windows so each three-minute checkpoint can
        // select a bounded current window plus two earlier ones.
        val windowCount = 5
        val activeWindow = chapterIndex.coerceIn(0, windowCount - 1)
        val paragraphsPerWindow = ((paragraphs.size + windowCount - 1) / windowCount).coerceAtLeast(1)
        val startWindow = (activeWindow - 2).coerceAtLeast(0)
        val startParagraph = (startWindow * paragraphsPerWindow).coerceAtMost(paragraphs.size)
        val endParagraph = ((activeWindow + 1) * paragraphsPerWindow).coerceAtMost(paragraphs.size)
        val selected = paragraphs.subList(startParagraph, endParagraph).joinToString("\n\n")
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = activeWindow,
            chapterTitle = "Reading window ${activeWindow + 1} of $windowCount",
            recentText = selected.takeLast(maxCharacters),
            sourceFormat = "txt"
        )
    }

    private fun extractEpub(book: BookEntity, chapterIndex: Int, maxCharacters: Int): LearningContext {
        val extracted = File(context.cacheDir, "epub/${book.id}")
        val parsed = try {
            epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // Readium opens EPUBs the strict parser can reject (unusual container.xml,
            // odd spine, missing NCX). Fall back to reading the raw XHTML entries so
            // card generation still works for books the user can actually read.
            return extractEpubRaw(book, maxCharacters)
        }
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        val start = (active - 2).coerceAtLeast(0)
        val text = ZipFile(book.localPath).use { zip ->
            parsed.chapters.subList(start, active + 1).joinToString("\n\n") { chapter ->
                val entry = zip.getEntry(chapter.filePath)
                    ?: zip.getEntry(chapter.filePath.substringBefore('#'))
                val plain = entry?.let { item ->
                    runCatching {
                        zip.getInputStream(item).use { input ->
                            Jsoup.parse(input, Charsets.UTF_8.name(), "").text()
                        }
                    }.getOrNull().orEmpty()
                }.orEmpty()
                "${chapter.title}\n$plain"
            }
        }
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = active,
            chapterTitle = parsed.chapters[active].title,
            recentText = text.takeLast(maxCharacters),
            sourceFormat = "epub"
        )
    }

    /**
     * Last-resort EPUB extraction when the strict parser rejects the file: read the
     * most recent XHTML content entries directly from the archive. Not chapter-aware,
     * but it guarantees cards still have real source text to work from.
     */
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
                    !(lower.contains("nav") || lower.contains("toc") ||
                        lower.contains("cover") || lower.contains("titlepage"))
                }
                .sortedBy { it.name }
                .toList()
                .takeLast(3)
                .joinToString("\n\n") { entry ->
                    runCatching {
                        zip.getInputStream(entry).use { input ->
                            Jsoup.parse(input, Charsets.UTF_8.name(), "").text()
                        }
                    }.getOrNull().orEmpty()
                }
        }
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = 0,
            chapterTitle = "Reading window",
            recentText = content.takeLast(maxCharacters),
            sourceFormat = "epub"
        )
    }
}
