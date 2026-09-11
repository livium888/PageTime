package com.pagetime.app.ui.screens.reader

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.pagetime.app.data.local.TextHighlightEntity
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val EPUB_HIGHLIGHT_GROUP = "pagetime-highlights"

/**
 * Renders the book's persistent highlights through Readium's decoration API.
 *
 * Each highlight is a selection Locator; Readium draws the highlight rects for
 * its offsets, which inside one resource can span several rendered pages. The
 * group is re-applied whenever the navigator, the highlight set, or the page
 * changes — the same lifecycle the concept decoration layer uses — so marks
 * survive page turns and re-entry.
 */
@Composable
fun EpubHighlightDecorationLayer(
    navigator: EpubNavigatorFragment?,
    highlights: List<TextHighlightEntity>,
    currentLocator: Locator?
) {
    LaunchedEffect(navigator, highlights, currentLocator) {
        val nav = navigator ?: return@LaunchedEffect
        if (!nav.supportsDecorationStyle(Decoration.Style.Highlight::class)) return@LaunchedEffect
        if (currentLocator == null) {
            nav.applyDecorations(emptyList(), EPUB_HIGHLIGHT_GROUP)
            return@LaunchedEffect
        }

        val decorations = highlights.mapNotNull { highlight ->
            val json = highlight.startLocatorJson ?: return@mapNotNull null
            val locator = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
                ?: return@mapNotNull null
            Decoration(
                id = "highlight-${highlight.id}",
                locator = locator,
                style = Decoration.Style.Highlight(
                    tint = Color.rgb(124, 179, 66)
                ),
                extras = emptyMap()
            )
        }
        nav.applyDecorations(decorations, EPUB_HIGHLIGHT_GROUP)
    }
}