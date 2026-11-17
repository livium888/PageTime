package com.pagetime.app.ui.screens.flashcards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.anki.AnkiReviewDialog
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity
import kotlinx.coroutines.launch

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
    /** Opens the slip box: the notes written while reading, with their links and boxes. */
    onOpenSlipBox: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    /** Jumps straight into the most overdue concept, in whichever book it lives in — see [DueConceptEntry]. */
    onExplainConcept: (bookId: String, chapterIndex: Int, chapterTitle: String, bookTitle: String) -> Unit = { _, _, _, _ -> },
    vm: FlashcardsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAnkiReviewer by remember { mutableStateOf(false) }

    // Two rooms: today's practice, and the cards themselves. The dashboard used
    // to sit above the list and take two thirds of the screen, leaving a strip
    // for the cards; now the list has the whole screen when you want it.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val allCards = remember(state.groups) { state.groups.flatMap { it.cards } }
    val selectedCards = allCards.filter { it.id in selected }
    // A card that has left the list (filtered out, deleted elsewhere) cannot stay selected.
    val selecting = selectedCards.isNotEmpty()

    fun removeWithUndo(cards: List<LearningCardEntity>) {
        if (cards.isEmpty()) return
        vm.remove(cards)
        selected = emptySet()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = if (cards.size == 1) "Card deleted" else "${cards.size} cards deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(cards)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selectedCards.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        if (selectedCards.any { it.status == LearningCardEntity.STATUS_PENDING }) {
                            IconButton(onClick = { vm.keepAll(selectedCards); selected = emptySet() }) {
                                Icon(Icons.Filled.Check, contentDescription = "Keep selected")
                            }
                        }
                        IconButton(onClick = { confirmDeleteSelected = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Flashcards") },
                    actions = {
                        // Anki keeps its own plain button rather than a live
                        // count: a due count here would mean querying
                        // AnkiDroid's ContentProvider across every deck just
                        // to render a badge, on every visit to this tab, and
                        // the extra schedule query is exactly what the grading
                        // fix earlier had to remove to stop corrupting
                        // AnkiDroid's own scheduler. It stays a separate,
                        // non-reactive door in, as Anki's queue is not
                        // PageTime's.
                        TextButton(onClick = { showAnkiReviewer = true }) { Text("Anki") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Practise") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Cards (${state.total})") })
            }

            if (tab == 0) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Cards and concepts are PageTime's own two systems, both
                    // cheap reactive counts; one combined number answers "how
                    // much is there today" before asking which to start with.
                    TodaysPractice(
                        cardsDue = state.dueTotal,
                        conceptsDue = state.dueConceptCount,
                        onReview = onOpenReview,
                        onExplainConcept = {
                            state.nextDueConcept?.let { due ->
                                onExplainConcept(due.bookId, due.chapterIndex, due.chapterTitle, due.bookTitle)
                            }
                        },
                    )
                    if (state.dueTotal + state.dueConceptCount <= 0) {
                        Text(
                            "Nothing is due today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    SlipBoxRow(count = state.slipCount, due = state.slipsDue, onOpen = onOpenSlipBox)
                    // What the reader has actually remembered. Shown only once
                    // there is something to report: "0% of 0 reviews" is a fact
                    // about having just started, not about the reader.
                    state.tally.recall?.let { recall ->
                        Text(
                            "You have remembered ${(recall * 100).toInt()}% of " +
                                "${state.tally.reviews} reviews across " +
                                "${state.tally.cards} card${if (state.tally.cards == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            } else {
                CardsTab(
                    state = state,
                    vm = vm,
                    selected = selected,
                    selecting = selecting,
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onDelete = { removeWithUndo(it) },
                    onOpenBook = onOpenBook,
                )
            }
        }
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete ${selectedCards.size} card${if (selectedCards.size == 1) "" else "s"}?") },
            text = { Text("You can undo this for a few seconds afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    removeWithUndo(selectedCards)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }

    state.editingCardId?.let { id ->
        allCards.firstOrNull { it.id == id }?.let { card ->
            CardEditor(
                card = card,
                onSave = { prompt, answer -> vm.saveEdit(card, prompt, answer) },
                onCancel = { vm.cancelEditing() },
                onDelete = { vm.cancelEditing(); removeWithUndo(listOf(card)) },
                onOpenBook = { vm.cancelEditing(); onOpenBook(card.bookId) },
            )
        }
    }

    if (showAnkiReviewer) {
        AnkiReviewDialog(onDismiss = { showAnkiReviewer = false })
    }
}

/** The whole screen as a list of cards: search, filters, and a row per card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardsTab(
    state: FlashcardsUiState,
    vm: FlashcardsViewModel,
    selected: Set<String>,
    selecting: Boolean,
    onToggle: (String) -> Unit,
    onDelete: (List<LearningCardEntity>) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    var bookMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text("Search cards") },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
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
            if (state.bookChoices.size > 1) {
                Box {
                    FilterChip(
                        selected = state.bookId != null,
                        onClick = { bookMenu = true },
                        label = {
                            Text(
                                state.bookChoices.firstOrNull { it.first == state.bookId }?.second?.take(22)
                                    ?: "All books",
                            )
                        },
                    )
                    DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("All books") },
                            onClick = { vm.setBook(null); bookMenu = false },
                        )
                        state.bookChoices.forEach { (id, title) ->
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = { vm.setBook(id); bookMenu = false },
                            )
                        }
                    }
                }
            }
        }

        when {
            state.loading -> Unit
            state.total == 0 -> Empty()
            state.groups.isEmpty() -> Centered(
                if (state.query.isNotBlank()) "No card matches \u201c${state.query.trim()}\u201d."
                else "Nothing under ${state.filter.label.lowercase()}."
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.groups.forEach { group ->
                    item(key = "book-${group.bookId}") {
                        Text(
                            "${group.bookTitle} (${group.cards.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBook(group.bookId) }
                                .padding(horizontal = 16.dp)
                                .padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(group.cards, key = { it.id }) { card ->
                        FlashcardRow(
                            card = card,
                            selecting = selecting,
                            selected = card.id in selected,
                            onToggleSelect = { onToggle(card.id) },
                            onKeep = { vm.keep(card) },
                            onDelete = { onDelete(listOf(card)) },
                            onEdit = { vm.startEditing(card) },
                            onOpenBook = { onOpenBook(card.bookId) },
                        )
                    }
                }
            }
        }
    }
}

/** Edit a card's words; see where it came from and how it has gone; or delete it. */
@Composable
private fun CardEditor(
    card: LearningCardEntity,
    onSave: (prompt: String, answer: String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpenBook: () -> Unit,
) {
    val isCloze = card.cardType == LearningCardEntity.TYPE_CLOZE
    var prompt by remember(card.id) { mutableStateOf(card.prompt) }
    var answer by remember(card.id) { mutableStateOf(card.answer) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isCloze) "Edit cloze card" else "Edit card") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(if (isCloze) "Sentence" else "Question") },
                    supportingText = if (isCloze) {
                        { Text("The part inside {{c1::…}} is hidden when you practise.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isCloze) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        label = { Text("Answer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                card.sourceQuote?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "From the book: \u201c${it.trim()}\u201d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (card.reviewCount == 0) "Not practised yet"
                    else "Practised ${card.reviewCount} time${if (card.reviewCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenBook) { Text("Open in book") }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank() && (isCloze || answer.isNotBlank()),
                onClick = { onSave(prompt, answer) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
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
 * The way into the slip box from Recall.
 *
 * Notes and questions are different things and stay different: a question is
 * asked of you, a note is something you wrote to think with, with a place in a
 * box, links to other notes and a trail of where you met the idea again. What
 * they share is the schedule, so they share this tab and one review sitting.
 * The notes are opened here rather than listed among the questions, so a note
 * is never mistaken for a card with a question on it.
 */
@Composable
private fun SlipBoxRow(count: Int, due: Int, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Slip box", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        count == 0 -> "Notes you write while reading, linked together"
                        due > 0 -> "$count note${if (count == 1) "" else "s"} · $due due, reviewed with your cards"
                        else -> "$count note${if (count == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Open", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * One card, closed by default.
 *
 * The answer is behind a tap even here. A list that shows every answer is a
 * list the reader reads instead of recalling, and quietly spends the cards
 * they were saving.
 *
 * Swipe left deletes (with Undo); swipe right keeps a card still waiting to be
 * judged. A long press starts picking several.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FlashcardRow(
    card: LearningCardEntity,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onOpenBook: () -> Unit,
) {
    var open by remember(card.id) { mutableStateOf(false) }
    val isCloze = card.cardType == LearningCardEntity.TYPE_CLOZE
    val pending = card.status == LearningCardEntity.STATUS_PENDING

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.StartToEnd -> { onKeep(); false }
                else -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = pending && !selecting,
        enableDismissFromEndToStart = !selecting,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        backgroundContent = {
            val toDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (toDelete) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(if (toDelete) "Delete" else "Keep", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() else open = !open },
                    onLongClick = onToggleSelect,
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                if (selecting) {
                    Box(
                        Modifier
                            .padding(end = 10.dp, top = 2.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            ),
                    ) {
                        if (selected) Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                maxLines = 1,
                            )
                        }
                    }

                    Text(
                        if (isCloze) ClozeText.blanked(card.prompt) else card.prompt,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (open) Int.MAX_VALUE else 3,
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
                                "\u201c${quote.trim()}\u201d",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onEdit) { Text("Edit") }
                            if (pending) {
                                Button(onClick = onKeep) { Text("Keep it") }
                            }
                            TextButton(onClick = onDelete) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
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
