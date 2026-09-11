package com.pagetime.app.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pagetime.app.data.TextHighlightSpans
import com.pagetime.app.data.learning.ConceptHighlightMatcher
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.TextHighlightEntity

/**
 * Builds an [AnnotatedString] where concept keywords found in [pageText] are
 * annotated with a subtle highlight style. Used by the plain-text reader to
 * provide ambient concept recognition — the same feature that
 * [com.pagetime.app.ui.screens.reader.EpubConceptDecorationLayer] provides for
 * EPUB books via Readium's decoration API.
 *
 * The highlight color adapts to the reader palette:
 * - "active" mode: warm gold, semibold
 * - "subtle" mode (default): soft blue, normal weight
 *
 * The entire operation is local string matching — no Gemini API call.
 */
@Composable
fun rememberAnnotatedPage(
    pageText: String,
    concepts: List<ConceptEntity>,
    level: String,
    activeConceptId: String?
): AnnotatedString {
    val hits = remember(pageText, concepts, level) {
        if (level == "off" || concepts.isEmpty() || pageText.isBlank()) {
            emptyList()
        } else {
            val maxHits = if (level == "active") 10 else 5
            ConceptHighlightMatcher.findHighlights(concepts, pageText, maxHits)
        }
    }

    if (hits.isEmpty()) {
        return AnnotatedString(pageText)
    }

    val highlightColor = if (level == "active") {
        Color(0xFFD4A017) // warm gold
    } else {
        Color(0xFF5279BE) // soft blue
    }

    val highlightWeight = if (level == "active") FontWeight.SemiBold else FontWeight.Normal

    return buildAnnotatedString {
        var cursor = 0
        for (hit in hits) {
            if (hit.startOffset > cursor) {
                append(pageText.substring(cursor, hit.startOffset))
            }
            if (hit.startOffset >= cursor && hit.startOffset + hit.length <= pageText.length) {
                val isActive = hit.conceptId == activeConceptId
                val bgColor = if (isActive) highlightColor.copy(alpha = 0.25f) else Color.Transparent
                withStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = highlightWeight,
                        background = bgColor
                    )
                ) {
                    append(pageText.substring(hit.startOffset, hit.startOffset + hit.length))
                }
                cursor = hit.startOffset + hit.length
            }
        }
        if (cursor < pageText.length) {
            append(pageText.substring(cursor))
        }
    }
}

/** The background tint for reader-marked highlights. */
private val ReaderHighlightBackground = Color(0xFF7CB342).copy(alpha = 0.30f)

/**
 * The page with persistent highlights merged in.
 *
 * Highlights are whole-book offsets; [pageStartOffset]..[pageEndOffset] is the
 * page's window onto the text. Concept hints and reader highlights are two
 * separate style layers and both survive the merge: overlapping spans simply
 * carry both style annotations, which Compose merges per property at draw.
 */
@Composable
fun rememberAnnotatedPageWithHighlights(
    pageText: String,
    pageStartOffset: Int,
    pageEndOffset: Int,
    highlights: List<TextHighlightEntity>,
    concepts: List<ConceptEntity>,
    level: String,
    activeConceptId: String?
): AnnotatedString {
    val ranges = remember(pageText, pageStartOffset, pageEndOffset, highlights) {
        TextHighlightSpans.txtPageRanges(highlights, pageStartOffset, pageEndOffset)
    }
    val concept = rememberAnnotatedPage(pageText, concepts, level, activeConceptId)
    if (ranges.isEmpty()) return concept

    val background = remember(pageText, ranges) {
        buildAnnotatedString {
            var cursor = 0
            for ((start, end) in ranges) {
                // The page text is trimmed at layout time, so a range touching
                // the page edge may run past the rendered text; clamp it.
                val safeStart = start.coerceIn(0, pageText.length)
                val safeEnd = end.coerceIn(safeStart, pageText.length)
                if (safeEnd <= safeStart) continue
                if (safeStart > cursor) append(pageText.substring(cursor, safeStart))
                withStyle(SpanStyle(background = ReaderHighlightBackground)) {
                    append(pageText.substring(safeStart, safeEnd))
                }
                cursor = safeEnd
            }
            if (cursor < pageText.length) append(pageText.substring(cursor))
        }
    }
    return remember(pageText, concept.spanStyles, background.spanStyles) {
        AnnotatedString(
            text = pageText,
            spanStyles = concept.spanStyles + background.spanStyles,
            paragraphStyles = concept.paragraphStyles
        )
    }
}
