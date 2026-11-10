package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagetime.app.data.learning.ReadingSpan

/**
 * Make flashcards from the stretch you just read, using your own LLM.
 *
 * Three steps, each on its own screen of this one sheet so there is only ever
 * one thing to do: copy the prompt, paste the model's reply, tick the cards to
 * keep. The reader leaves the app between the first two, so nothing here relies
 * on staying open — see [ManualCardsViewModel].
 *
 * The span's first and last words are shown because the position in an EPUB is
 * only approximate; seeing the edges is how the reader notices a boundary that
 * is a paragraph off and corrects it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakeCardsSheet(
    vm: ManualCardsViewModel,
    onDismiss: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmStartHere by remember { mutableStateOf(false) }

    // A reply shared straight from the AI app is read the same way as a pasted one.
    if (state.step == ManualCardsViewModel.Step.SPAN) {
        com.pagetime.app.ui.components.SharedReplyEffect(vm::reviewReply)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cards from this stretch", style = MaterialTheme.typography.titleLarge)

            when (state.step) {
                ManualCardsViewModel.Step.SPAN -> SpanStep(
                    state = state,
                    onCopy = vm::copyPrompt,
                    onShare = { vm.sharePrompt(context) },
                    onPaste = { vm.pasteReply(context) },
                    onNudge = vm::nudgeEnd,
                    onStartHere = { confirmStartHere = true },
                    onStartOver = vm::startOver,
                )
                ManualCardsViewModel.Step.REVIEW -> ReviewStep(
                    state = state,
                    onToggle = vm::toggle,
                    onAdd = vm::addChosen,
                    onBack = vm::backToSpan,
                )
                ManualCardsViewModel.Step.DONE -> DoneStep(
                    added = state.added,
                    onReview = {
                        onDismiss()
                        onOpenReview()
                    },
                    onClose = onDismiss,
                )
            }
        }
    }

    // Dropping the text above the current page cannot be undone, so it asks.
    if (confirmStartHere) {
        AlertDialog(
            onDismissRequest = { confirmStartHere = false },
            title = { Text("Start from this page?") },
            text = {
                Text(
                    "Everything you read before this page will be left out of these cards. " +
                        "You can't get it back into this batch."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartHere = false
                    vm.startFromHere()
                }) { Text("Start here") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartHere = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun SpanStep(
    state: ManualCardsViewModel.UiState,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPaste: () -> Unit,
    onNudge: (Int) -> Unit,
    onStartHere: () -> Unit,
    onStartOver: () -> Unit,
) {
    val slice = state.slice

    if (slice == null) {
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("Finding what you read…")
            }
        }
        state.error?.let { ErrorLine(it) }
        return
    }

    Text(
        "Copy what you read with a ready-made prompt, paste it into your LLM app, then bring " +
            "its reply back here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.markerJustSet) {
        Notice("Your start was set at this page. Read on, then come back to make cards.")
    }

    SpanCard(state = state, slice = slice)

    when {
        slice.backwards -> Warning(
            "You're before your start. Go forward to where you stopped, or start from this page."
        )
        slice.tooShort -> Warning(
            "Only ${slice.words} words so far. Read a little more first; a few paragraphs is plenty."
        )
        slice.tooLong -> Warning(
            "That's ${"%,d".format(slice.words)} words, more than can be copied at once " +
                "(${"%,d".format(ReadingSpan.MAX_WORDS)} at most). Start from this page."
        )
        slice.long -> Warning(
            "That's a long stretch (${"%,d".format(slice.words)} words). A model covers long " +
                "text unevenly; starting from this page gives better cards."
        )
    }

    if (!slice.backwards && !slice.isEmpty) {
        EndNudger(
            enabled = !state.awaitingReply && !state.busy,
            onLess = { onNudge(-1) },
            onMore = { onNudge(1) },
            locked = state.awaitingReply,
        )
    }

    state.notice?.let { Notice(it) }
    state.error?.let { ErrorLine(it) }

    val canSend = !slice.backwards && !slice.tooShort && !slice.tooLong && !state.busy

    if (state.awaitingReply) {
        Button(onClick = onPaste, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Paste the reply")
        }
        OutlinedButton(onClick = onCopy, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
            Text("Copy the prompt again")
        }
        TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Start over with a different stretch")
        }
    } else {
        Button(onClick = onCopy, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
            Text("Copy prompt and text")
        }
        OutlinedButton(onClick = onShare, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
            Text("Share to an app…")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onStartHere, enabled = !state.busy) { Text("Start from this page") }
            TextButton(onClick = onPaste, enabled = !state.busy) { Text("I have a reply") }
        }
    }
}

@Composable
private fun SpanCard(state: ManualCardsViewModel.UiState, slice: ReadingSpan.Slice) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (slice.isEmpty) {
                Text(
                    if (slice.backwards) "Nothing between your start and here."
                    else "Nothing new since your start yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Edge(label = "From", words = slice.firstWords, chapter = state.fromLabel)
                Edge(label = "To", words = slice.lastWords, chapter = state.toLabel)
                HorizontalDivider()
                Text(
                    "About ${"%,d".format(slice.words)} words · asks for up to ${state.cardsAsked} cards",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Edge(label: String, words: String, chapter: String?) {
    Column {
        Text(
            label + (chapter?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("“$words”", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Moves the end of the span a paragraph at a time.
 *
 * Needed because an EPUB position is only approximate: the span can end a
 * paragraph early or late, and this is how the reader corrects what they can
 * see in the "To" line. Locked once the prompt is copied, because the reply
 * will be checked against exactly the text that was sent.
 */
@Composable
private fun EndNudger(enabled: Boolean, onLess: () -> Unit, onMore: () -> Unit, locked: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("End of the text", style = MaterialTheme.typography.labelLarge)
            Text(
                if (locked) "Fixed: this is what you copied." else "Move it a paragraph if it's off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onLess, enabled = enabled) { Text("− Less") }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = onMore, enabled = enabled) { Text("+ More") }
    }
}

@Composable
private fun ReviewStep(
    state: ManualCardsViewModel.UiState,
    onToggle: (Int) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        "${state.review.size} card${if (state.review.size == 1) "" else "s"} passed the checks. " +
            "Untick any you don't want.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.review.forEachIndexed { index, card ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = card.keep,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(index) },
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // The row is the control; the checkbox only shows its state.
                Checkbox(checked = card.keep, onCheckedChange = null)
                Spacer(Modifier.size(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.raw.prompt, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (card.raw.isCloze) "Hidden: ${card.raw.answer}" else card.raw.answer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (card.raw.explanation.isNotBlank()) {
                        Text(
                            card.raw.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (state.leftOut.isNotEmpty()) {
        LeftOut(state)
    }
    state.problems.forEach { Warning(it) }
    state.error?.let { ErrorLine(it) }

    Button(
        onClick = onAdd,
        enabled = state.chosen > 0 && !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.chosen == 0) "Add cards" else "Add ${state.chosen} card${if (state.chosen == 1) "" else "s"}")
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

/** Cards the checks refused, with the reason for each, never silently dropped. */
@Composable
private fun LeftOut(state: ManualCardsViewModel.UiState) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("${state.leftOut.size} left out${if (open) " (hide)" else " (see why)"}")
    }
    if (open) {
        Text(
            "Every card is checked against the exact text you copied. A card whose quote isn't " +
                "in it, or that breaks a card-writing rule, is left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.leftOut.forEach { (raw, reason) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(raw.prompt, style = MaterialTheme.typography.bodyMedium)
                Text(
                    reason.reason.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DoneStep(added: Int, onReview: () -> Unit, onClose: () -> Unit) {
    Text(
        "$added card${if (added == 1) "" else "s"} added.",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "They're due now. Your next batch starts right after the text these came from.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("Review them now") }
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Keep reading") }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
