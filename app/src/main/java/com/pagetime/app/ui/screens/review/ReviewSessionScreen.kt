package com.pagetime.app.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.review.ReviewSessionState

/**
 * Answering the cards that are due.
 *
 * THIS DID NOT EXIST
 *
 * The scheduler, the ratings, the due query and the grading were all built and
 * tested; nothing ever called them. The slip box showed a chip counting due
 * cards whose tap handler selected the first box. FSRS had been scheduling
 * cards into a room with no door for as long as it had been installed.
 *
 * The answer is deliberately hidden until asked for. A card whose answer is
 * already on screen is not a test of anything — the reader reads it, feels
 * recognition, and rates themselves generously. The pause before revealing is
 * the entire mechanism.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSessionScreen(
    onBack: () -> Unit,
    onOpenSource: (bookId: String) -> Unit = {},
    /** A due chunk is handed to the reader rather than answered here. */
    onReadChunk: (bookId: String) -> Unit = {},
    vm: ReviewSessionViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.finishedOrEmpty) {
                        Text(
                            "${state.session.graduated.size}/${state.session.started}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.finishedOrEmpty) {
                LinearProgressIndicator(
                    progress = { state.session.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val card = state.card
            when {
                state.loading -> Centered("Finding what is due…")

                card == null -> Done(state.session, onBack)

                card.isChunk -> ChunkReviewContent(
                    card = card,
                    onRead = { vm.readChunk { onReadChunk(card.bookId) } },
                    onSkip = vm::skip,
                )

                else -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        // Which book asked this, not just that a book did. A
                        // sitting now mixes questions from several books, and
                        // some prompts are ambiguous without knowing the
                        // subject — through no fault of the reader.
                        Text(
                            card.sourceLabel
                                ?: if (card.fromChapter) "From the book" else "From your slip box",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            card.front,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )

                        if (state.revealed) {
                            Text(card.back, style = MaterialTheme.typography.bodyLarge)
                            // The answer says what; this says why. It is the
                            // part worth reading on the tenth review, when the
                            // answer itself is long since automatic.
                            card.explanation?.let { why ->
                                Spacer(Modifier.height(10.dp))
                                Text(why, style = MaterialTheme.typography.bodyMedium)
                            }
                            card.source?.takeIf { it.isNotBlank() }?.let { source ->
                                Spacer(Modifier.height(10.dp))
                                // Labelled, because unlabelled it read as the
                                // explanation — which is exactly what it was
                                // standing in for while the explanation column
                                // went unfilled.
                                Text(
                                    "From the book",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    source.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (card.bookId.isNotBlank()) {
                                TextButton(onClick = { onOpenSource(card.bookId) }) {
                                    Text("Open the passage")
                                }
                            }
                        }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // The interval and the way back from it, side by side.
                        // A rating is not a display state: it rewrites the
                        // card's difficulty and stability, and "Again" on a
                        // card you actually knew costs weeks of interval that
                        // nothing else gives back.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.lastInterval?.let { "Last card returns $it" } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.canUndo) {
                                TextButton(onClick = vm::undo) { Text("Undo that") }
                            }
                        }
                        if (!state.revealed) {
                            Button(
                                onClick = vm::reveal,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Show answer") }
                            TextButton(
                                onClick = vm::skip,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Skip for now") }
                        } else {
                            // Four ratings rather than right/wrong: FSRS uses
                            // the difference to decide how far to push the next
                            // interval, and collapsing them throws that away.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LumenRating.entries.forEach { rating ->
                                    val label = rating.label
                                    if (rating == LumenRating.AGAIN) {
                                        Button(
                                            onClick = { vm.grade(rating) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(label, maxLines = 1) }
                                    } else {
                                        OutlinedButton(
                                            onClick = { vm.grade(rating) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(label, maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ReviewUiState.finishedOrEmpty: Boolean
    get() = !loading && card == null

/**
 * A due chunk, offered for re-reading.
 *
 * Deliberately not the card layout: a chunk has no answer to reveal and no
 * rating to give here. The rating belongs to the reader's close-chunk flow,
 * where the passage is fresh — the same rule FirstReview applies to the
 * reading chair. The sitting's only job is to surface the reminder and hand
 * the reader to the passage.
 */
@Composable
private fun ChunkReviewContent(
    card: ReviewItem,
    onRead: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            card.sourceLabel ?: "From your reading queue",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            card.front,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "This chunk is due for re-reading. Open it, read it again, and " +
                "close it with a rating when you finish — it comes back later " +
                "on the same schedule as your flashcards.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onRead,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Read chunk") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Skip for now") }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Done(session: ReviewSessionState, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.started == 0) {
                Text("Nothing is due.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Questions appear here once you keep one while reading, put a " +
                        "slip box card into training, or finish a reading chunk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Done.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${session.started} card${if (session.started == 1) "" else "s"}" +
                        if (session.lapses > 0) {
                            ", ${session.lapses} of them more than once."
                        } else {
                            "."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
