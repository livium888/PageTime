package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.local.LearningCardEntity
import kotlin.math.roundToInt

/**
 * Every question waiting in this chapter, and where each one will appear.
 *
 * Surfacing questions one at a time as the reader reaches them is the right
 * default, and it was the whole of the first version — which meant a reader who
 * had just generated five of them could see none, and reasonably concluded
 * nothing had been made. Being able to look at the set is not a compromise of
 * the design; it is the difference between trusting it and not.
 *
 * The answers are deliberately NOT shown here. This is a list of what is
 * coming, not a way to read the answers before being asked — that would spend
 * the questions before they are put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterPromptListSheet(
    prompts: List<LearningCardEntity>,
    progression: Float,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Questions for this chapter", style = MaterialTheme.typography.titleLarge)
            Text(
                "Each one appears when you reach the passage it came from. " +
                    "Answers are hidden until then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            items(prompts, key = { it.id }) { card ->
                val at = ((card.sourceFraction ?: 0f) * 100).roundToInt()
                val passed = progression >= (card.sourceFraction ?: 0f)
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (passed) "Ready now" else "At $at% of the chapter",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (passed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        card.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
