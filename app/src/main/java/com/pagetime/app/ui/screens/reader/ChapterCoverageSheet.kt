package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.local.ChapterPassageEntity
import com.pagetime.app.data.local.PassageOutcome
import kotlin.math.roundToInt

/**
 * Every passage the chapter sent, and what became of it.
 *
 * WHY THIS EXISTS
 *
 * Reported from the device: twenty-four passages went out and two cards came
 * back. The app could show both numbers and nothing in between, so "why?" had
 * no answer and — worse — the reader had no way to disagree. Twenty-two
 * paragraphs of their book had been silently judged not worth remembering by a
 * model, and they could not even see which ones.
 *
 * So this is a list of the passages, what happened to each, and a way to say
 * "no — make me a card for these six". The reader overrules the model rather
 * than regenerating and hoping.
 *
 * WHY THE PASSAGE TEXT IS SHOWN
 *
 * Because judging the decision needs the evidence. A row saying "the model
 * skipped this" is unactionable; the paragraph itself lets the reader see in a
 * second whether the model was right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterCoverageSheet(
    passages: List<ChapterPassageEntity>,
    busy: Boolean,
    onMakeCards: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pre-selecting nothing is deliberate. This sheet exists because a machine
    // made a sweeping choice on the reader's behalf; opening it with boxes
    // already ticked would be the same mistake in the other direction.
    var chosen by remember(passages) { mutableStateOf(emptySet<Int>()) }
    val barren = passages.filter { it.cardsMade == 0 }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("What this chapter was asked", style = MaterialTheme.typography.titleLarge)
            Text(
                "${passages.size} passages were sent. " +
                    "${passages.count { it.cardsMade > 0 }} produced a question. " +
                    "Tick any that should have, and ask again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (barren.isNotEmpty()) {
                TextButton(
                    onClick = {
                        chosen = if (chosen.containsAll(barren.map { it.ordinal })) {
                            emptySet()
                        } else {
                            barren.map { it.ordinal }.toSet()
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Select all ${barren.size} with no question")
                }
            }
        }

        HorizontalDivider()

        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(passages, key = { it.ordinal }) { passage ->
                PassageRow(
                    passage = passage,
                    checked = passage.ordinal in chosen,
                    onToggle = {
                        chosen = if (passage.ordinal in chosen) {
                            chosen - passage.ordinal
                        } else {
                            chosen + passage.ordinal
                        }
                    },
                )
                HorizontalDivider()
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { onMakeCards(chosen) },
                enabled = chosen.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busy -> "Writing…"
                        chosen.isEmpty() -> "Pick some passages"
                        else -> "Make a question for ${chosen.size} passage" +
                            if (chosen.size == 1) "" else "s"
                    }
                )
            }
            Text(
                "This asks again for the ticked passages only, and tells the " +
                    "model not to skip them. It costs another request.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PassageRow(
    passage: ChapterPassageEntity,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val outcome = PassageOutcome.of(passage.outcome)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "At ${(passage.progression * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    verdict(outcome, passage),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (passage.cardsMade > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // The paragraph itself, trimmed. Judging whether the model was
            // right to skip something is impossible without seeing it.
            Text(
                passage.text.trim().take(240).let {
                    if (passage.text.trim().length > 240) "$it…" else it
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** One short line saying what happened, in the reader's terms rather than the enum's. */
private fun verdict(outcome: PassageOutcome?, passage: ChapterPassageEntity): String = when (outcome) {
    PassageOutcome.USED ->
        "${passage.cardsMade} question" + if (passage.cardsMade == 1) "" else "s"
    PassageOutcome.MODEL_SKIPPED -> "the model wrote nothing"
    // The rule that refused it, in words, because "rejected" alone tells the
    // reader nothing they can act on.
    PassageOutcome.REJECTED -> passage.detail?.let { "thrown out — $it" } ?: "thrown out"
    PassageOutcome.REQUEST_FAILED -> "the request failed"
    null -> "no question"
}
