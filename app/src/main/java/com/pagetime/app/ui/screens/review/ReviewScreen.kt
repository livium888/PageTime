package com.pagetime.app.ui.screens.review

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LearningRating
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.masteryLabel
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    viewModel: ReviewViewModel = viewModel()
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val revealed by viewModel.revealed.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val titles by viewModel.bookTitles.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sourceToOpen by viewModel.sourceToOpen.collectAsStateWithLifecycle()
    val selectedMcqOption by viewModel.selectedMcqOption.collectAsStateWithLifecycle()
    val mcqResult by viewModel.mcqResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(sourceToOpen) {
        sourceToOpen?.let {
            onOpenSource(it.bookId)
            viewModel.clearSourceToOpen()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh due cards")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LearningStatsSummary(stats.totalCards, stats.dueCards, stats.successfulReviews)
                if (cards.isEmpty()) {
                    EmptyReview(Modifier.fillMaxWidth().padding(top = 40.dp))
                } else {
                    val card = cards.first()
                    val cardTypeLabel = when (card.cardType) {
                        "cloze" -> "Cloze deletion"
                        "mcq" -> "Multiple choice"
                        else -> "Active recall"
                    }

                    // Card type badge + metadata
                    CardHeader(card, titles, cardTypeLabel)

                    // Card content — renders differently per type
                    when (card.cardType) {
                        "mcq" -> McqCardContent(
                            card = card,
                            selectedOption = selectedMcqOption,
                            result = mcqResult,
                            revealed = revealed,
                            onSelectOption = viewModel::selectMcqOption,
                            onReveal = viewModel::reveal,
                            onOpenSource = { viewModel.openSource(card) }
                        )
                        "cloze" -> ClozeCardContent(
                            card = card,
                            revealed = revealed,
                            onReveal = viewModel::reveal,
                            onOpenSource = { viewModel.openSource(card) }
                        )
                        else -> QaCardContent(
                            card = card,
                            revealed = revealed,
                            onReveal = viewModel::reveal,
                            onOpenSource = { viewModel.openSource(card) }
                        )
                    }

                    // Rating buttons — always shown after reveal
                    if (revealed) {
                        RatingSection(onRate = viewModel::rate)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(
    card: LearningCardEntity,
    titles: Map<String, String>,
    cardTypeLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Card type chip
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                cardTypeLabel,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        Text(titles[card.bookId] ?: "Book", style = MaterialTheme.typography.titleMedium)
        Text(
            card.chapterTitle ?: "Chapter ${card.chapterIndex + 1}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("${card.masteryLabel()} · ${card.reviewCount} reviews", style = MaterialTheme.typography.labelMedium)
        // Keep topic and source context off the front of the card. Both can reveal
        // the answer before the reader has attempted recall.
    }
}

// ─── Cloze Deletion Card ──────────────────────────────────────────────

@Composable
private fun ClozeCardContent(
    card: LearningCardEntity,
    revealed: Boolean,
    onReveal: () -> Unit,
    onOpenSource: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Render the cloze text with the {{c1::answer}} pattern highlighted
            val clozeText = card.prompt
            if (clozeText.contains("{{c1::")) {
                val before = clozeText.substringBefore("{{c1::")
                val hidden = clozeText.substringAfter("{{c1::").substringBefore("}}")
                val after = clozeText.substringAfter("}}")
                if (revealed) {
                    // Show the answer highlighted inline
                    Text(
                        buildAnnotatedString {
                            append(before)
                            withStyle(SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )) { append(hidden) }
                            append(after)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                } else {
                    // Show blank
                    Text(
                        buildAnnotatedString {
                            append(before)
                            withStyle(SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                background = MaterialTheme.colorScheme.primaryContainer
                            )) { append("_____") }
                            append(after)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            } else {
                Text(card.prompt, style = MaterialTheme.typography.headlineSmall)
            }

            if (!revealed) {
                Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                    Text("Complete the blank")
                }
            } else {
                Text("Answer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(card.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                card.explanation?.let {
                    Text("Explanation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                SourceDisclosure(card, onOpenSource)
            }
        }
    }
}

// ─── Multiple Choice Card ─────────────────────────────────────────────

@Composable
private fun McqCardContent(
    card: LearningCardEntity,
    selectedOption: String?,
    result: Boolean?,
    revealed: Boolean,
    onSelectOption: (String) -> Unit,
    onReveal: () -> Unit,
    onOpenSource: () -> Unit
) {
    val options = remember(card.mcqOptions) {
        card.mcqOptions?.let { parseOptions(it) } ?: emptyList()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.prompt, style = MaterialTheme.typography.headlineSmall)

            if (!revealed && options.isNotEmpty()) {
                // Show selectable options
                options.forEach { option ->
                    val isSelected = selectedOption == option
                    val bgColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    Surface(
                        onClick = { onSelectOption(option) },
                        shape = RoundedCornerShape(14.dp),
                        color = bgColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                    ) {
                        Text(
                            option,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else if (revealed && options.isNotEmpty()) {
                // Show options with correct/incorrect highlighting
                options.forEach { option ->
                    val isCorrect = option.equals(card.answer, ignoreCase = true)
                    val isSelected = selectedOption == option
                    val bgColor = when {
                        isCorrect -> Color(0xFFDCFCE7) // green-50
                        isSelected && !isCorrect -> Color(0xFFFEE2E2) // red-50
                        else -> MaterialTheme.colorScheme.surface
                    }
                    val borderColor = when {
                        isCorrect -> Color(0xFF22C55E) // green-500
                        isSelected && !isCorrect -> Color(0xFFEF4444) // red-500
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = bgColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isCorrect) {
                                Icon(Icons.Filled.Check, contentDescription = "Correct",
                                    tint = Color(0xFF22C55E))
                            } else if (isSelected) {
                                Icon(Icons.Filled.Close, contentDescription = "Incorrect",
                                    tint = Color(0xFFEF4444))
                            }
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                // Explanation after answer
                card.explanation?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Why", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                SourceDisclosure(card, onOpenSource)
            }
        }
    }
}

// ─── Standard Q&A Card ────────────────────────────────────────────────

@Composable
private fun QaCardContent(
    card: LearningCardEntity,
    revealed: Boolean,
    onReveal: () -> Unit,
    onOpenSource: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(card.prompt, style = MaterialTheme.typography.headlineSmall)
            if (revealed) {
                Text("Answer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(card.answer, style = MaterialTheme.typography.bodyLarge)
                card.explanation?.let {
                    Text("Why", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                SourceDisclosure(card, onOpenSource)
            } else {
                Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("Reveal answer") }
            }
        }
    }
}

// ─── Shared Components ────────────────────────────────────────────────

@Composable
private fun SourceDisclosure(card: LearningCardEntity, onOpenSource: () -> Unit) {
    val hasQuote = !card.sourceQuote.isNullOrBlank()
    val hasLocation = card.sourceLocator != null || card.sourceFraction != null
    if (!hasQuote && !hasLocation) return

    var expanded by remember(card.id) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide context" else "See where this came from")
    }
    if (expanded) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "From this passage",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                card.sourceQuote?.takeIf { it.isNotBlank() }?.let {
                    Text("\"$it\"", style = MaterialTheme.typography.bodySmall)
                }
                if (hasLocation) {
                    OutlinedButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
                        Text("Open source passage")
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingSection(onRate: (LearningRating) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How well did you remember it?", style = MaterialTheme.typography.titleMedium)
        LearningRating.entries.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { rating ->
                    val primary = rating == LearningRating.GOOD || rating == LearningRating.EASY
                    if (primary) {
                        Button(
                            onClick = { onRate(rating) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(rating.label, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onRate(rating) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(rating.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Text(
            "Your rating changes only this card's future review time. It does not change reading progress or earned time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LearningStatsSummary(total: Int, due: Int, successful: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatValue("Due", due.toString())
            StatValue("Cards", total.toString())
            StatValue("Successful", successful.toString())
        }
    }
}

@Composable
private fun StatValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyReview(modifier: Modifier) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(4.dp))
        Text("No cards are due yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Keep reading for about three minutes; a small comprehension checkpoint will appear automatically. You can also use Reader → Options → Generate cards now.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Parse the JSON array of MCQ options from the stored string. */
private fun parseOptions(json: String): List<String> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
