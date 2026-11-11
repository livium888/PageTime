package com.pagetime.app.ui.screens.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.learning.ClozeBuilder
import com.pagetime.app.data.learning.ClozeText

/**
 * Makes a fill-in-the-blank card from a highlight: tap the words to hide.
 *
 * No AI and no leaving the app. The preview underneath is the card exactly as
 * review will show it, so there is no guessing what was made.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClozeFromHighlightSheet(
    quote: String,
    onSave: (chosen: Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val words = remember(quote) { ClozeBuilder.words(quote) }
    var chosen by remember(quote) { mutableStateOf(emptySet<Int>()) }
    val cloze = ClozeBuilder.build(words, chosen)

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
            Text("Make a card", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tap the word or words to hide. Review will show the sentence with a gap " +
                    "and ask you for what is missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                words.forEachIndexed { index, word ->
                    val on = index in chosen
                    Text(
                        word,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { chosen = if (on) chosen - index else chosen + index }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            if (cloze != null) {
                Text("The card", style = MaterialTheme.typography.labelLarge)
                Text(ClozeText.blanked(cloze), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Answer: ${ClozeBuilder.answer(cloze)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (chosen.isNotEmpty()) {
                Text(
                    "Leave some of the sentence showing, so there is something to answer from.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Button(
                onClick = { onSave(chosen) },
                enabled = cloze != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add card") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
