package com.pagetime.app.data.learning

import android.content.Context
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
        val text = ZipFile(book.localPath).use { zip ->
            val entry = zip.getEntry(chapter.filePath)
                ?: zip.getEntry(chapter.filePath.substringBefore('#'))
            val plain = entry?.let { item ->
                runCatching {
                    zip.getInputStream(item).use { input ->
                        Jsoup.parse(input, Charsets.UTF_8.name(), "").text()
                    }
                }.getOrNull().orEmpty()
            }.orEmpty()
            val raw = "${chapter.title}\n$plain"
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

    private fun checkpointFraction(checkpoint: LearningCheckpoint?, chapterPath: String): Float =
        checkpoint?.locatorJson
            ?.let { locator ->
                if (locator.contains(chapterPath.substringBefore('#'))) {
                    Regex("\\\"progression\\\"\\s*:\\s*([0-9.]+)").find(locator)
                        ?.groupValues?.getOrNull(1)?.toFloatOrNull()
                } else null
            }?.coerceIn(0f, 1f) ?: 0f

    private fun currentFraction(locatorJson: String?, chapterPath: String): Float =
        locatorJson
            ?.takeIf { it.contains(chapterPath.substringBefore('#')) }
            ?.let { locator ->
                Regex("\\\"progression\\\"\\s*:\\s*([0-9.]+)").find(locator)
                    ?.groupValues?.getOrNull(1)?.toFloatOrNull()
            }?.coerceIn(0f, 1f) ?: 1f

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
