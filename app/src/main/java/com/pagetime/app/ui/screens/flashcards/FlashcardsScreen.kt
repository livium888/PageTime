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
import com.pagetime.app.anki.AnkiReviewDialog
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
    /** Jumps straight into the most overdue concept, in whichever book it lives in — see [DueConceptEntry]. */
    onExplainConcept: (bookId: String, chapterIndex: Int, chapterTitle: String, bookTitle: String) -> Unit = { _, _, _, _ -> },
    vm: FlashcardsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAnkiReviewer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flashcards") },
                actions = {
                    // Anki keeps its own plain button rather than a live
                    // count: unlike cards and concepts, a due count here
                    // would mean querying AnkiDroid's ContentProvider across
                    // every deck just to render a badge, on every visit to
                    // this tab — real cross-app IPC cost, and exactly the
                    // kind of extra schedule query the grading fix earlier
                    // had to eliminate to stop corrupting AnkiDroid's own
                    // live scheduler state. It stays a separate, non-reactive
                    // door in for the same reason it stays a separate
                    // scheduling system: it is Anki's queue, not PageTime's.
                    TextButton(onClick = { showAnkiReviewer = true }) { Text("Anki") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Cards and concepts are PageTime's own two systems, both cheap,
            // reactive counts — unlike Anki, merging them into one headline
            // number doesn't misrepresent anything, it just answers "how
            // much is there today" before asking which one to start with.
            TodaysPractice(
                cardsDue = state.due,
                conceptsDue = state.dueConceptCount,
                onReview = onOpenReview,
                onExplainConcept = {
                    state.nextDueConcept?.let { due ->
                        onExplainConcept(due.bookId, due.chapterIndex, due.chapterTitle, due.bookTitle)
                    }
                },
            )
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

    if (showAnkiReviewer) {
        AnkiReviewDialog(onDismiss = { showAnkiReviewer = false })
    }
}

/**
 * One answer to "how much is there to do today", combining PageTime's own
 * two systems instead of leaving the reader to add up two separate buttons.
 *
 * Cards and concepts stay separate actions underneath, deliberately: a
 * flashcard answer and a written explanation are different enough tasks that
 * forcing them into one literal swipe-through session would misrepresent
 * both, the same reason Anki keeps its own button rather than joining this
 * count. What was actually missing wasn't one combined queue — it was one
 * combined number, so the reader sees the whole day's practice at a glance
 * before picking which part to start with.
 */
@Composable
private fun TodaysPractice(
    cardsDue: Int,
    conceptsDue: Int,
    onReview: () -> Unit,
    onExplainConcept: () -> Unit,
) {
    val total = cardsDue + conceptsDue
    if (total <= 0) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Today's practice: $total ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    if (cardsDue > 0) append("$cardsDue card${if (cardsDue == 1) "" else "s"}")
                    if (cardsDue > 0 && conceptsDue > 0) append(" · ")
                    if (conceptsDue > 0) {
                        append("$conceptsDue concept${if (conceptsDue == 1) "" else "s"} to explain")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cardsDue > 0) {
                    Button(onClick = onReview) { Text("Cards ($cardsDue)") }
                }
                if (conceptsDue > 0) {
                    Button(onClick = onExplainConcept) { Text("Concepts ($conceptsDue)") }
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
