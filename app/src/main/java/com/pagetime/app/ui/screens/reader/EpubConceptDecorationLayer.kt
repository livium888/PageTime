package com.pagetime.app.ui.screens.reader

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.pagetime.app.data.local.ConceptEntity
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val EPUB_CONCEPT_GROUP = "pagetime-concepts"

/**
 * Owns only transient Readium decorations. It never edits the EPUB and clears the
 * group whenever the resource, chapter, or hint level changes.
 */
@Composable
fun EpubConceptDecorationLayer(
    navigator: EpubNavigatorFragment?,
    concepts: List<ConceptEntity>,
    chapterIndex: Int?,
    currentLocator: Locator?,
    level: String,
    onConceptActivated: (String) -> Unit
) {
    val currentOnActivated by rememberUpdatedState(onConceptActivated)

    DisposableEffect(navigator) {
        if (navigator == null) {
            onDispose { }
        } else {
            val listener = object : DecorableNavigator.Listener {
                override fun onDecorationActivated(
                    event: DecorableNavigator.OnActivatedEvent
                ): Boolean {
                    val conceptId = event.decoration.extras["conceptId"] as? String ?: return false
                    currentOnActivated(conceptId)
                    return true
                }
            }
            navigator.addDecorationListener(EPUB_CONCEPT_GROUP, listener)
            onDispose {
                navigator.removeDecorationListener(listener)
            }
        }
    }

    LaunchedEffect(navigator, concepts, chapterIndex, currentLocator, level) {
        val nav = navigator ?: return@LaunchedEffect
        if (!nav.supportsDecorationStyle(Decoration.Style.Highlight::class)) return@LaunchedEffect
        val locator = currentLocator
        val chapter = chapterIndex
        if (locator == null || chapter == null) {
            nav.applyDecorations(emptyList(), EPUB_CONCEPT_GROUP)
            return@LaunchedEffect
        }

        val anchors = EpubConceptHints.anchors(
            concepts = concepts,
            chapterIndex = chapter,
            level = level
        )
        val tint = if (level == "active") {
            Color.rgb(255, 193, 7)
        } else {
            Color.rgb(82, 121, 190)
        }
        val decorations = anchors.map { anchor ->
            Decoration(
                id = "concept-${anchor.conceptId}",
                locator = locator.copy(
                    locations = Locator.Locations(),
                    text = Locator.Text(
                        before = anchor.before,
                        highlight = anchor.phrase,
                        after = anchor.after
                    )
                ),
                style = Decoration.Style.Highlight(tint = tint),
                extras = mapOf<String, Any>("conceptId" to anchor.conceptId)
            )
        }
        nav.applyDecorations(decorations, EPUB_CONCEPT_GROUP)
    }
}
