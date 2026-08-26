package com.pagetime.app.data.learning

import android.content.Context
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.library.EpubParser
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

/**
 * Builds the text window Gemini sees. Each chapter is processed at most once and
 * the result is cached, so the extractor sends the *full* current chapter — no
 * windowing across earlier chapters. This maximises question quality because the
 * AI can reference any part of the chapter in a single call.
 */
class LearningContextExtractor(
    private val context: Context,
    private val epubParser: EpubParser
) {
    /**
     * Returns the full text of [chapterIndex].  A generous safety cap (40k chars)
     * prevents accidental uploads of abnormally long chapters while still
     * covering most real EPUB chapters in one pass.
     */
    fun extract(book: BookEntity, chapterIndex: Int, maxCharacters: Int = 40_000): LearningContext {
        require(maxCharacters in 2_000..80_000)
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
        // book as five stable windows and return the active one in full.
        val windowCount = 5
        val activeWindow = chapterIndex.coerceIn(0, windowCount - 1)
        val paragraphsPerWindow = ((paragraphs.size + windowCount - 1) / windowCount).coerceAtLeast(1)
        val startParagraph = (activeWindow * paragraphsPerWindow).coerceAtMost(paragraphs.size)
        val endParagraph = ((activeWindow + 1) * paragraphsPerWindow).coerceAtMost(paragraphs.size)
        val selected = paragraphs.subList(startParagraph, endParagraph).joinToString("\n\n")
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = activeWindow,
            chapterTitle = "Reading window ${activeWindow + 1} of $windowCount",
            recentText = selected.take(maxCharacters),
            sourceFormat = "txt"
        )
    }

    private fun extractEpub(book: BookEntity, chapterIndex: Int, maxCharacters: Int): LearningContext {
        val extracted = File(context.cacheDir, "epub/${book.id}")
        val parsed = try {
            epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return extractEpubRaw(book, maxCharacters)
        }
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        // Send the full active chapter — no windowing across prior chapters.
        val text = ZipFile(book.localPath).use { zip ->
            val chapter = parsed.chapters[active]
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
        return LearningContext(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = active,
            chapterTitle = parsed.chapters[active].title,
            recentText = text.take(maxCharacters),
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
