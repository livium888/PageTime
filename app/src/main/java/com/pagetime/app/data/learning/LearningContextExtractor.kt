package com.pagetime.app.data.learning

import android.content.Context
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.library.EpubParser
import java.io.File
import java.util.zip.ZipFile
import org.jsoup.Jsoup

/**
 * Builds the only text window Gemini is allowed to see. The active chapter plus the
 * prior two chapters gives enough continuity to identify topics without uploading a
 * whole book or unrelated earlier content.
 */
class LearningContextExtractor(
    private val context: Context,
    private val epubParser: EpubParser
) {
    fun extract(book: BookEntity, chapterIndex: Int, maxCharacters: Int = 24_000): LearningContext {
        require(maxCharacters in 4_000..40_000)
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
        // book as five stable reading windows so the trigger and extractor agree:
        // at 20/40/60/80/100%, Gemini sees the current window plus two earlier ones.
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
        val parsed = epubParser.parse(File(book.localPath), extracted, extractAssets = false)
        val active = chapterIndex.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
        val start = (active - 2).coerceAtLeast(0)
        val text = ZipFile(book.localPath).use { zip ->
            parsed.chapters.subList(start, active + 1).joinToString("\n\n") { chapter ->
                val entry = zip.getEntry(chapter.filePath)
                    ?: zip.getEntry(chapter.filePath.substringBefore('#'))
                val plain = entry?.let { item ->
                    zip.getInputStream(item).use { input ->
                        Jsoup.parse(input, Charsets.UTF_8.name(), "").text()
                    }
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
}
