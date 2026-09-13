package com.pagetime.app.ui.screens.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.review.CardTextSize
import com.pagetime.app.data.review.ReviewSessionState

/**
 * Answering the cards that are due.
 *
 * THIS DID NOT EXIST
 *
 * The scheduler, the ratings, the due query and the grading were all built and
 * tested; nothing ever called them. The slip box showed a chip counting due
 * cards whose tap handler selected the first box. FSRS had been scheduling
 * cards into a room with no door for as long as it had been installed.
 *
 * The answer is deliberately hidden until asked for. A card whose answer is
 * already on screen is not a test of anything — the reader reads it, feels
 * recognition, and rates themselves generously. The pause before revealing is
 * the entire mechanism.
 *
 * MOSTLY CARD, BECAUSE THE CARD IS THE POINT
 *
 * The screen used to spend a third of itself on fixed furniture: a two-row grid
 * of buttons with the interval captions on a line of their own beneath them, a
 * permanent row for "last card returns…", and a permanent note about the
 * browsing reward. The reading area that was left had to be scrolled to read a
 * three-line answer.
 *
 * It is now arranged the way Anki arranges it, for Anki's reasons: the card is a
 * panel that takes the whole middle of the screen, the four ratings are one row
 * with their intervals printed inside them, and everything that is not a button
 * or a card has moved to the bar at the top. See [AnswerBar] for the button
 * geometry and why the captions live where they do.
 *
 * The chrome is roughly a fifth of the screen instead of a third, and the
 * difference is about six more lines of answer text before anything scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSessionScreen(
    onBack: () -> Unit,
    onOpenSource: (bookId: String) -> Unit = {},
    /** A due chunk is handed to the reader rather than answered here. */
    onReadChunk: (bookId: String) -> Unit = {},
    vm: ReviewSessionViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cardTextSize by vm.cardTextSize.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    // Whether the answer still needs bringing into view.
    //
    // The scroll range only exists once the answer has been laid out, and that
    // happens a frame or two after the tap. Waiting on `maxValue` rather than
    // sleeping for a guessed number of milliseconds means this works on the
    // long cards that need it and stays out of the way on the short ones.
    var answerNeedsScroll by remember { mutableStateOf(false) }
    LaunchedEffect(state.revealed, state.card?.id) {
        if (state.revealed) {
            answerNeedsScroll = true
        } else {
            // A new card starts at its own top, wherever the last one ended.
            answerNeedsScroll = false
            scrollState.scrollTo(0)
        }
    }
    LaunchedEffect(scrollState.maxValue, answerNeedsScroll) {
        if (answerNeedsScroll && scrollState.maxValue > 0) {
            answerNeedsScroll = false
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.inSitting) {
                        CountsBar(remaining = state.session.queue.size)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.inSitting) {
                        CardTextSizeMenu(current = cardTextSize, onPick = vm::setCardTextSize)
                        SittingMenu(
                            canUndo = state.canUndo,
                            onUndo = vm::undo,
                            onSkip = vm::skip,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.inSitting) {
                // Thin on purpose: it is a position in the sitting, not a
                // loading state, and a full-height bar would outrank the card.
                LinearProgressIndicator(
                    progress = { state.session.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }

            val card = state.card
            when {
                state.loading -> Centered("Finding what is due…")

                card == null -> Done(state.session, onBack)

                card.isChunk -> ChunkReviewContent(
                    card = card,
                    onRead = { vm.readChunk { onReadChunk(card.bookId) } },
                )

                else -> CardReviewContent(
                    card = card,
                    state = state,
                    textSize = cardTextSize,
                    scrollState = scrollState,
                    onReveal = vm::reveal,
                    onGrade = vm::grade,
                    onOpenSource = onOpenSource,
                )
            }
        }
    }
}

/**
 * A card is on screen and can be answered.
 *
 * False while the due query is still running and on the done screen — two
 * different situations that both mean "there is nothing to put a count or a
 * rating under", and that a reader should not be shown a row of zeroes for.
 */
private val ReviewUiState.inSitting: Boolean
    get() = !loading && card != null

/**
 * The question, the answer, and the row of ratings — and nothing else.
 *
 * The footer holds one control in each state: "Show answer" before the answer
 * is out, the four ratings after. Both are the same height, so revealing a card
 * does not move the buttons under the reader's thumb.
 */
@Composable
private fun ColumnScope.CardReviewContent(
    card: ReviewItem,
    state: ReviewUiState,
    textSize: CardTextSize,
    scrollState: ScrollState,
    onReveal: () -> Unit,
    onGrade: (LumenRating) -> Unit,
    onOpenSource: (bookId: String) -> Unit,
) {
    CardPanel(Modifier.weight(1f)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Which book asked this, not just that a book did. A sitting now
            // mixes questions from several books, and some prompts are
            // ambiguous without knowing the subject — through no fault of the
            // reader.
            Text(
                card.sourceLabel
                    ?: if (card.fromChapter) "From the book" else "From your slip box",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                card.front,
                style = scaled(MaterialTheme.typography.titleLarge, textSize),
                fontWeight = FontWeight.SemiBold,
            )

            if (state.revealed) {
                Text(
                    card.back,
                    style = scaled(MaterialTheme.typography.bodyLarge, textSize),
                )
                // The answer says what; this says why. It is the part worth
                // reading on the tenth review, when the answer itself is long
                // since automatic.
                card.explanation?.let { why ->
                    Text(
                        why,
                        style = scaled(MaterialTheme.typography.bodyMedium, textSize),
                    )
                }
                card.source?.takeIf { it.isNotBlank() }?.let { source ->
                    Spacer(Modifier.height(2.dp))
                    // Labelled, because unlabelled it read as the explanation —
                    // which is exactly what it was standing in for while the
                    // explanation column went unfilled.
                    Text(
                        "From the book",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Deliberately NOT scaled with the card. This is the
                    // provenance of the question, not the question; it is the
                    // first thing that should give way when the reader asks for
                    // bigger text.
                    Text(
                        source.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.bookId.isNotBlank()) {
                    TextButton(onClick = { onOpenSource(card.bookId) }) {
                        Text("Open the passage")
                    }
                }
            }
        }
    }

    SittingFooter(state = state, onReveal = onReveal, onGrade = onGrade)
}

/**
 * The one control the card needs, plus one line of feedback above it.
 *
 * The line is [reviewFeedbackLine] — what the last answer cost and what it
 * bought — and it is only there when it has something to say. It used to be two
 * permanent rows, "Last card returns tomorrow at 9am" and "30s per answer · 90s
 * earned this session", which together held forty-odd dp of the screen to say
 * something that is only news for a moment after a tap.
 *
 * The line sits ABOVE the button rather than below it so that when it appears
 * or clears, the button row stays where the thumb last left it and only the
 * card changes size.
 */
@Composable
private fun SittingFooter(
    state: ReviewUiState,
    onReveal: () -> Unit,
    onGrade: (LumenRating) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 14.dp),
    ) {
        reviewFeedbackLine(state)?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        if (!state.revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().height(AnswerButtonHeight),
                shape = AnswerButtonShape,
            ) {
                Text("Show answer", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // Four ratings rather than right/wrong: FSRS uses the difference to
            // decide how far to push the next interval, and collapsing them
            // throws that away.
            AnswerBar(
                ratings = LumenRating.entries,
                onGrade = onGrade,
                intervalPreviews = state.intervalPreviews,
            )
        }
    }
}

/** The one-line summary of the previous answer, or null when there is nothing to say. */
internal fun reviewFeedbackLine(state: ReviewUiState): String? {
    val parts = mutableListOf<String>()
    state.earnedFeedback?.takeIf { it.isNotBlank() }?.let(parts::add)
    state.lastInterval?.takeIf { it.isNotBlank() }?.let { parts += "next in $it" }
    // The running total is only worth reading next to the answer that just
    // added to it. On its own it is a counter with no event — which is exactly
    // what the permanent note it replaces had become.
    if (parts.isNotEmpty() && state.totalEarnedThisSitting > 0) {
        parts += "${state.totalEarnedThisSitting}s this sitting"
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/**
 * A raised panel that takes the space it is given.
 *
 * The card used to be text on the page background, which reads as an article.
 * A panel with an edge reads as an object being turned over, which is what it
 * is — and it is the single change that stops a long question from looking like
 * the whole screen.
 */
@Composable
private fun CardPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        // An edge, not a lift. Surface and background are about 8% apart in
        // luminance in both themes; see AppCard, which does the same thing for
        // the same reason.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
    }
}

/**
 * The remaining count, in the accent colour the way Anki colours its queue
 * counts.
 *
 * Only one number, because there is only one queue. Anki can show new, learning
 * and review separately because it has three; printing an invented taxonomy
 * here to look more like Anki would be a lie about the reader's own data. The
 * thin bar under the top bar carries the other half of the story — how far
 * through the sitting they are — which a count alone cannot show.
 */
@Composable
private fun CountsBar(remaining: Int) {
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            ) { append(remaining.toString()) }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(if (remaining == 1) " card left" else " cards left")
            }
        },
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
    )
}

@Composable
private fun CardTextSizeMenu(current: CardTextSize, onPick: (CardTextSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.FormatSize, contentDescription = "Card text size")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CardTextSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(size.label) },
                    trailingIcon = {
                        if (size == current) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        }
                    },
                    onClick = {
                        onPick(size)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Undo and skip, in the bar rather than under the card.
 *
 * Both were buttons on the card, and both are things you reach for once in a
 * while rather than every card — so they were paying permanent rent on the
 * reading area. This is also where Anki keeps them, in the reviewer's overflow
 * menu, and the reason is the same.
 *
 * Undo is disabled rather than hidden when there is nothing to take back: a
 * menu whose entries appear and disappear teaches the reader that the menu is
 * unreliable, and the one time they need it they will not look.
 */
@Composable
private fun SittingMenu(canUndo: Boolean, onUndo: () -> Unit, onSkip: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Undo last answer") },
                enabled = canUndo,
                onClick = {
                    onUndo()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Skip for now") },
                onClick = {
                    onSkip()
                    open = false
                },
            )
        }
    }
}

/**
 * Multiplies a text style by the reader's chosen card size.
 *
 * Size and line height together, never one without the other: scaling the type
 * alone is what produces a card that is either cramped at the large end or
 * full of holes at the small one. At Medium the style is returned untouched,
 * which is also the guarantee that the default setting renders exactly the
 * typography the rest of the app uses.
 */
private fun scaled(base: TextStyle, size: CardTextSize): TextStyle =
    if (size.scale == 1f) {
        base
    } else {
        base.copy(
            fontSize = base.fontSize * size.scale,
            lineHeight = base.lineHeight * size.scale,
        )
    }

/**
 * A due chunk, offered for re-reading.
 *
 * Deliberately not the card layout: a chunk has no answer to reveal and no
 * rating to give here. The rating belongs to the reader's close-chunk flow,
 * where the passage is fresh — the same rule FirstReview applies to the
 * reading chair. The sitting's only job is to surface the reminder and hand
 * the reader to the passage.
 */
@Composable
private fun ColumnScope.ChunkReviewContent(card: ReviewItem, onRead: () -> Unit) {
    CardPanel(Modifier.weight(1f)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                card.sourceLabel ?: "From your reading queue",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                card.front,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This chunk is due for re-reading. Open it, read it again, and " +
                    "close it with a rating when you finish — it comes back later " +
                    "on the same schedule as your flashcards.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    // One button, and a footer the same height as a card's, so moving between a
    // card and a chunk does not move the screen under the reader.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 14.dp),
    ) {
        Button(
            onClick = onRead,
            modifier = Modifier.fillMaxWidth().height(AnswerButtonHeight),
            shape = AnswerButtonShape,
        ) {
            Text("Read chunk", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Done(session: ReviewSessionState, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.started == 0) {
                Text("Nothing is due.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Questions appear here once you keep one while reading, put a " +
                        "slip box card into training, or finish a reading chunk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Done.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${session.started} card${if (session.started == 1) "" else "s"}" +
                        if (session.lapses > 0) {
                            ", ${session.lapses} of them more than once."
                        } else {
                            "."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
