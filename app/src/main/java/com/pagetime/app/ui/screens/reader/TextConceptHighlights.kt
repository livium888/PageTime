package com.pagetime.app.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pagetime.app.data.learning.ConceptHighlightMatcher
import com.pagetime.app.data.local.ConceptEntity

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
