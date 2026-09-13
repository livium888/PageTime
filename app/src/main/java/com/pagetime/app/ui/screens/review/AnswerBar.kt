package com.pagetime.app.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.LumenRating

/**
 * The row of rating buttons, in AnkiDroid's shape.
 *
 * WHY ONE ROW AND NOT A GRID
 *
 * AnkiDroid's answer buttons are four equal-weight buttons on a single line,
 * each the minimum touch height, and the next interval is printed INSIDE the
 * button it belongs to. That is the whole trick: the caption costs no vertical
 * space at all, because it is drawn in a button that had to exist anyway. A
 * grid of two rows with the captions underneath spends roughly twice the height
 * to say the same thing, and every dp of it comes out of the card — which is
 * the only part of the screen the reader is actually here for.
 *
 * The structure below follows `AnswerButton.kt` in Anki-Android: the interval
 * is appended at 0.8x the label's size, then a newline, then the label. The
 * ratio matters more than the pixel value; it is what keeps the number legible
 * as a number while never competing with the word under it.
 *
 * THE ONE PLACE THIS APP IS NOT MONOCHROME
 *
 * Everywhere else the palette is warm paper, deep ink and a single teal accent,
 * and hue is never used to mean anything. Here it is, deliberately: four
 * answers that differ in cost need to be distinguishable at a glance, without
 * reading, because they are pressed several hundred times in a sitting. This is
 * also exactly what Anki does, and a reader arriving from Anki already knows
 * that red is the bad one. The four hues are tuned down from AnkiDroid's
 * saturated set so they sit on this palette rather than fighting it, and the
 * "right answer" (Good) wears the app's own accent, which reads as green here.
 *
 * [AnswerBar] is shared with the in-reader question card, so the same answer is
 * in the same place, in the same colour, wherever the reader meets a question.
 */
@Composable
fun AnswerBar(
    ratings: List<LumenRating>,
    onGrade: (LumenRating) -> Unit,
    modifier: Modifier = Modifier,
    /** Next-interval captions by rating; a rating with no caption shows the label alone. */
    intervalPreviews: Map<LumenRating, String> = emptyMap(),
    /** What the button says under the interval. Differs between the sitting and the chair. */
    label: (LumenRating) -> String = { it.label },
    buttonHeight: Dp = AnswerButtonHeight,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnswerButtonGap),
    ) {
        ratings.forEach { rating ->
            val tint = answerTint(rating)
            Button(
                onClick = { onGrade(rating) },
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                shape = AnswerButtonShape,
                // AnkiDroid sets android:padding to 0 on these. Four buttons
                // across a phone leave about 80dp each, and the default M3
                // horizontal padding would eat most of it.
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tint.container,
                    contentColor = tint.content,
                ),
            ) {
                Text(
                    text = answerButtonText(
                        lines = answerButtonLines(intervalPreviews[rating], label(rating)),
                        intervalSize = MaterialTheme.typography.labelLarge.fontSize * AnswerIntervalRelativeSize,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = AnswerLineCount,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the button says, as lines: the interval above the label.
 *
 * Split out from the drawing so the decision — and in particular the decision
 * about a rating with no caption — is testable without a phone.
 */
internal fun answerButtonLines(interval: String?, label: String): List<String> =
    if (interval.isNullOrBlank()) listOf(label) else listOf(interval, label)

/**
 * Draws [lines] as one string, with the first line (the interval) at
 * [intervalSize] and the rest at the style's own size.
 */
internal fun answerButtonText(lines: List<String>, intervalSize: TextUnit) = buildAnnotatedString {
    lines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (index == 0 && lines.size > 1) {
            withStyle(SpanStyle(fontSize = intervalSize)) { append(line) }
        } else {
            append(line)
        }
    }
}

/**
 * The four button colours, by rating.
 *
 * Two tables rather than a lighten/darken of one, because a tint that merely
 * has its luminance shifted goes muddy on a dark background: the container has
 * to get darker while the ink on it gets lighter, which is two independent
 * choices and not one.
 */
private data class RatingTint(val container: Color, val content: Color)

private val LightTints: Map<LumenRating, RatingTint> = mapOf(
    LumenRating.AGAIN to RatingTint(Color(0xFFB23A2E), Color(0xFFFFF5F3)),
    LumenRating.HARD to RatingTint(Color(0xFF8A6A32), Color(0xFFFFF8EC)),
    LumenRating.GOOD to RatingTint(Color(0xFF0F766E), Color(0xFFFFFFFF)),
    LumenRating.EASY to RatingTint(Color(0xFF2E5F86), Color(0xFFF2F8FD)),
)

private val DarkTints: Map<LumenRating, RatingTint> = mapOf(
    LumenRating.AGAIN to RatingTint(Color(0xFF7C2A21), Color(0xFFFFD9D2)),
    LumenRating.HARD to RatingTint(Color(0xFF63481F), Color(0xFFF7E4C2)),
    LumenRating.GOOD to RatingTint(Color(0xFF0E564E), Color(0xFFC8F1E8)),
    LumenRating.EASY to RatingTint(Color(0xFF1F4A6B), Color(0xFFCFE4F5)),
)

/**
 * Resolves a rating's colours against the scheme actually in force.
 *
 * Reads the background's luminance rather than the system's dark setting, so
 * the buttons cannot end up inverted if a surface is ever themed independently
 * of the system — the two would then disagree, and grey text on a pale button
 * is the result.
 *
 * An unknown rating gets the theme's own neutral rather than crashing or
 * inventing a colour: a new rating added to the enum without a tint shows up
 * plainly and finishes second in review, instead of breaking the build of the
 * screen that would have told you.
 */
@Composable
private fun answerTint(rating: LumenRating): RatingTint {
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return (if (onDark) DarkTints else LightTints)[rating]
        ?: RatingTint(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

/**
 * AnkiDroid's answer button geometry, for the same reason as its structure:
 * one line, minimum touch height, a hair of a gap between neighbours.
 *
 * 56dp rather than their 48dp because two lines of text in 48dp leaves no room
 * for the descenders, and the label is the thing being read.
 */
internal val AnswerButtonHeight = 56.dp
private val AnswerButtonGap = 4.dp
internal val AnswerButtonShape = RoundedCornerShape(14.dp)

/** `RelativeSizeSpan(0.8f)` in AnkiDroid's AnswerButton.kt. */
private const val AnswerIntervalRelativeSize = 0.8f

/** Interval and label. Anything longer is the label's fault, not the layout's. */
private const val AnswerLineCount = 2
