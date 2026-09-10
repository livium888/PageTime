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
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.review.FirstReview

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
 * THE ANSWER IS HIDDEN, THEN THE GRADING
 *
 * Two steps, deliberately in this order. Showing the answer first would make
 * the reader judge a question they never tried, and the only way to know
 * whether you knew something is to try to recall it. So: think, reveal, then
 * say how it went.
 *
 * HOW IT WENT, NOT WHETHER TO KEEP IT
 *
 * This card used to ask "Keep it" or "Throw it away", which is shopping. It
 * never asked the one question worth asking — did you know the answer — so the
 * card entered the deck with no history, fell due immediately, and the next
 * review sitting opened by asking something answered ten minutes ago. The
 * retrieval that matters most, taken while the passage is still warm, was
 * discarded.
 *
 * Now the buttons are FSRS ratings and answering IS accepting. See
 * [FirstReview] for why Easy is not among them.
 *
 * THROWING ONE AWAY IS STILL POSSIBLE, JUST DEMOTED
 *
 * Some generated questions are bad, and a reader who cannot say so is stuck
 * with them forever. But that is a judgement about the QUESTION, not about
 * their performance on it, so it sits apart from the ratings and is phrased as
 * such.
 */
@Composable
fun ChapterPromptCard(
    card: LearningCardEntity,
    stillAhead: Int,
    onGrade: (LumenRating) -> Unit,
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
                    // Why, before the evidence. Grading yourself honestly
                    // means knowing what the question was actually after, and
                    // the answer on its own rarely says.
                    card.explanation?.takeIf { it.isNotBlank() }?.let { why ->
                        Text(why, style = MaterialTheme.typography.bodyMedium)
                    }
                    card.sourceQuote?.takeIf { it.isNotBlank() && !isCloze }?.let { quote ->
                        // Labelled, because unlabelled it read as the
                        // explanation — which is exactly what it was standing
                        // in for while the explanation column went unfilled.
                        Text(
                            "From the book",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            quote.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "How did that go?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        chairRatings.forEach { rating ->
                            // Again is the filled one, as in the review
                            // sitting, so the same answer is in the same place
                            // wherever the reader meets a question.
                            if (rating == LumenRating.AGAIN) {
                                Button(
                                    onClick = { onGrade(rating) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(chairLabel(rating), maxLines = 1) }
                            } else {
                                OutlinedButton(
                                    onClick = { onGrade(rating) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(chairLabel(rating), maxLines = 1) }
                            }
                        }
                    }
                    TextButton(onClick = onSkip) { Text("Bad question — throw it away") }
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

/**
 * The ratings the reading chair offers, in the order the review sitting shows
 * them.
 *
 * Built from [FirstReview] rather than listed here, so the rule about Easy
 * lives in one place and is tested there.
 */
private val chairRatings: List<LumenRating> =
    FirstReview.OFFERED_IN_THE_CHAIR.mapNotNull { value ->
        LumenRating.entries.firstOrNull { it.value == value }
    }

/**
 * Plainer words than the review sitting uses.
 *
 * "Again" makes sense once you have met a card before and it means "show me
 * this again sooner". On a question you are seeing for the first time it means
 * nothing, so the chair says what actually happened.
 */
private fun chairLabel(rating: LumenRating): String = when (rating) {
    LumenRating.AGAIN -> "Missed it"
    LumenRating.HARD -> "Struggled"
    else -> "Knew it"
}
