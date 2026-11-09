package com.pagetime.app.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pagetime.app.data.TextHighlightSpans
import com.pagetime.app.data.learning.ConceptHighlightMatcher
import com.pagetime.app.data.library.MarkdownText
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
 * The background tint for the span being grabbed right now.
 *
 * The same hue as a saved highlight, laid down harder. A reader who has just
 * long-pressed a sentence is looking at two things at once — what is being
 * chosen and what was chosen before — and a second colour would read as two
 * kinds of highlight rather than one in progress. The weight does the work.
 */
private val ReaderGrabBackground = Color(0xFF7CB342).copy(alpha = 0.55f)

/**
 * The page with persistent highlights merged in.
 *
 * Highlights are whole-book offsets; [pageStartOffset]..[pageEndOffset] is the
 * page's window onto the text. Concept hints, saved highlights and the live
 * grab are separate style layers and all of them survive the merge: overlapping
 * spans simply carry several style annotations, which Compose resolves per
 * property at draw, and the last one wins where they collide.
 */
@Composable
fun rememberAnnotatedPageWithHighlights(
    pageText: String,
    pageStartOffset: Int,
    pageEndOffset: Int,
    highlights: List<TextHighlightEntity>,
    concepts: List<ConceptEntity>,
    level: String,
    activeConceptId: String?,
    /** The sentence being grabbed, as whole-book offsets, or null. */
    pendingSpan: Pair<Int, Int>? = null,
    /** Headings, bold and italic from pasted markdown, as whole-book offsets. */
    styleSpans: List<MarkdownText.StyleSpan> = emptyList(),
): AnnotatedString {
    val formatting = remember(pageText, pageStartOffset, pageEndOffset, styleSpans) {
        formattingLayer(pageText, MarkdownText.pageSpans(styleSpans, pageStartOffset, pageEndOffset))
    }
    val ranges = remember(pageText, pageStartOffset, pageEndOffset, highlights) {
        TextHighlightSpans.txtPageRanges(highlights, pageStartOffset, pageEndOffset)
    }
    val grab = remember(pageText, pageStartOffset, pageEndOffset, pendingSpan) {
        pendingSpan?.let {
            TextHighlightSpans.clipToPage(it.first, it.second, pageStartOffset, pageEndOffset)
        }
    }
    val concept = rememberAnnotatedPage(pageText, concepts, level, activeConceptId)
    if (ranges.isEmpty() && grab == null && formatting.isEmpty()) return concept

    val saved = remember(pageText, ranges) {
        backgroundLayer(pageText, ranges, ReaderHighlightBackground)
    }
    val selecting = remember(pageText, grab) {
        backgroundLayer(pageText, listOfNotNull(grab), ReaderGrabBackground)
    }
    return remember(pageText, formatting, concept.spanStyles, saved.spanStyles, selecting.spanStyles) {
        AnnotatedString(
            text = pageText,
            // Formatting first: it sets weight and slant only, and the layers
            // after it set colour and background, so none of them collide.
            spanStyles = formatting + concept.spanStyles + saved.spanStyles + selecting.spanStyles,
            paragraphStyles = concept.paragraphStyles
        )
    }
}

/**
 * The weight and slant for [spans], given page-relative.
 *
 * A heading is drawn bold at the body size, not larger. Pages are measured
 * from the plain text before any of this is applied, so a span that changed
 * the height of a line would push the end of the page off the screen.
 */
private fun formattingLayer(
    text: String,
    spans: List<MarkdownText.StyleSpan>,
): List<AnnotatedString.Range<SpanStyle>> = spans.mapNotNull { span ->
    val start = span.start.coerceIn(0, text.length)
    val end = span.end.coerceIn(start, text.length)
    if (end <= start) return@mapNotNull null
    val style = when (span.kind) {
        MarkdownText.Kind.HEADING, MarkdownText.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        MarkdownText.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    }
    AnnotatedString.Range(style, start, end)
}

/**
 * One background layer over [text], for [ranges] given page-relative.
 *
 * The ranges are already merged, so they never overlap and a single
 * left-to-right pass with a cursor lays them all down. A range touching a page
 * edge may run past the rendered text — the page text is trimmed at layout
 * time — so both ends are clamped rather than trusted.
 */
private fun backgroundLayer(
    text: String,
    ranges: List<Pair<Int, Int>>,
    color: Color
): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var cursor = 0
        for ((start, end) in ranges) {
            val safeStart = start.coerceIn(0, text.length)
            val safeEnd = end.coerceIn(safeStart, text.length)
            if (safeEnd <= safeStart) continue
            if (safeStart > cursor) append(text.substring(cursor, safeStart))
            withStyle(SpanStyle(background = color)) {
                append(text.substring(safeStart, safeEnd))
            }
            cursor = safeEnd
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
