package com.pagetime.app.ui.screens.flashcards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity

/**
 * Every flashcard the books have produced, in one place.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE SLIP BOX
 *
 * They are not the same thing and conflating them has already caused
 * confusion once. A Lumen card is a note the reader wrote to think with; a
 * flashcard is a question generated from a passage to be answered from memory.
 * The slip box is for connecting ideas, this is for keeping them.
 *
 * WHY IT EXISTS AT ALL
 *
 * Cards surfaced one at a time while reading, and nowhere else. That is right
 * for the moment a question is asked, and useless for the reader who wants to
 * know what they have — whether it worked, whether the questions are any good,
 * what is waiting. Answering "did that do anything?" needs a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardsScreen(
    onOpenReview: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    vm: FlashcardsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flashcards") },
                actions = {
                    if (state.due > 0) {
                        TextButton(onClick = onOpenReview) { Text("Review ${state.due}") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlashcardFilter.entries.forEach { filter ->
                    val count = state.counts[filter] ?: 0
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { vm.setFilter(filter) },
                        label = { Text("${filter.label} ($count)") },
                    )
                }
            }

            // What the reader has actually remembered. Shown only once there
            // is something to report: "0% of 0 reviews" is not a fact about
            // the reader, it is a fact about having just started.
            state.tally.recall?.let { recall ->
                Text(
                    "You have remembered ${(recall * 100).toInt()}% of " +
                        "${state.tally.reviews} reviews across " +
                        "${state.tally.cards} card${if (state.tally.cards == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                state.loading -> Unit

                state.total == 0 -> Empty()

                state.groups.isEmpty() -> Centered(
                    "Nothing under ${state.filter.label.lowercase()}."
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        item(key = "book-${group.bookId}") {
                            Text(
                                group.bookTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenBook(group.bookId) }
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group.cards, key = { it.id }) { card ->
                            FlashcardRow(
                                card = card,
                                onKeep = { vm.keep(card) },
                                onDiscard = { vm.discard(card) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One card, closed by default.
 *
 * The answer is behind a tap even here. A list that shows every answer is a
 * list the reader reads instead of recalling, and quietly spends the cards
 * they were saving.
 */
@Composable
private fun FlashcardRow(
    card: LearningCardEntity,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    var open by remember(card.id) { mutableStateOf(false) }
    val isCloze = card.cardType == LearningCardEntity.TYPE_CLOZE
    val pending = card.status == LearningCardEntity.STATUS_PENDING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { open = !open },
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isCloze) "Cloze" else "Question",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pending) {
                    Text(
                        "not judged yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                card.chapterTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                if (isCloze) ClozeText.blanked(card.prompt) else card.prompt,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            if (open) {
                Text(
                    if (isCloze) ClozeText.filled(card.prompt) else card.answer,
                    style = MaterialTheme.typography.bodyMedium,
                )
                card.sourceQuote?.takeIf { it.isNotBlank() && !isCloze }?.let { quote ->
                    // The sentence it came from, so a suspicious card can be
                    // checked against the book rather than trusted.
                    Text(
                        "“${quote.trim()}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pending) {
                        Button(onClick = onKeep) { Text("Keep it") }
                    }
                    OutlinedButton(onClick = onDiscard) { Text("Throw it away") }
                }
            }
        }
    }
}

@Composable
private fun Empty() {
    Centered(
        "No flashcards yet. Open a book, index it, then use " +
            "Options → Make questions for this chapter."
    )
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
