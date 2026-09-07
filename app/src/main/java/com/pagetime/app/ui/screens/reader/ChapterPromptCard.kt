package com.pagetime.app.ui.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity

/**
 * A question about the paragraph just read, offered rather than imposed.
 *
 * NOT A DIALOG
 *
 * A modal would stop the reading to demand an answer, and the one thing this
 * must not become is a toll booth on the page turn. It is a card at the foot of
 * the page: the reader can answer it, keep it, throw it away, or ignore it
 * entirely and carry on reading, and ignoring it costs nothing.
 *
 * THE ANSWER IS HIDDEN, THEN THE JUDGEMENT
 *
 * Two steps, deliberately in this order. Showing the answer first would make
 * the reader judge a question they never tried, and the only way to know
 * whether a prompt is any good is to attempt it. So: think, reveal, then decide
 * whether it is worth keeping.
 */
@Composable
fun ChapterPromptCard(
    card: LearningCardEntity,
    stillAhead: Int,
    onKeep: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(card.id) { mutableStateOf(false) }
    val isCloze = card.cardType == LearningCardEntity.TYPE_CLOZE

    // A new question arrives unrevealed even if the last one was open.
    LaunchedEffect(card.id) { revealed = false }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.elevatedCardColors(),
            elevation = CardDefaults.elevatedCardElevation(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "A question about what you just read",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (isCloze) ClozeText.blanked(card.prompt) else card.prompt,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (!revealed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { revealed = true }) { Text("Show answer") }
                        TextButton(onClick = onSkip) { Text("Not this one") }
                    }
                } else {
                    Text(
                        if (isCloze) ClozeText.filled(card.prompt) else card.answer,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    card.sourceQuote?.takeIf { it.isNotBlank() && !isCloze }?.let { quote ->
                        // The line it came from, so the reader can see for
                        // themselves that the card is not invented.
                        Text(
                            "“${quote.trim()}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onKeep) { Text("Keep it") }
                        OutlinedButton(onClick = onSkip) { Text("Throw it away") }
                    }
                }

                if (stillAhead > 0) {
                    // Said rather than left to be discovered, which is the
                    // difference between a feature and an ambush.
                    Text(
                        "$stillAhead more later in this chapter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
