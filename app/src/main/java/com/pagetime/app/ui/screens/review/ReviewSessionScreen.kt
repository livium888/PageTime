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

                else -> {
                    val (front, back) = vm.prompt(card)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        if (card.indexNumber.isNotBlank()) {
                            Text(
                                card.indexNumber,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            front,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )

                        if (state.revealed) {
                            Text(back, style = MaterialTheme.typography.bodyLarge)
                            if (card.quote.isNotBlank() && card.quote != back) {
                                Text(
                                    card.quote.trim(),
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
                        state.lastInterval?.let {
                            Text(
                                "Last card returns $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    "Cards appear here once you put them into training from the slip box.",
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
