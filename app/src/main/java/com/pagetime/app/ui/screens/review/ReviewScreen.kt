package com.pagetime.app.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LearningRating
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.masteryLabel

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
    var cardToDelete by remember { mutableStateOf<LearningCardEntity?>(null) }
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

                    // Card header with delete button
                    CardHeader(card, titles)

                    // Card content — Q&A only
                    QaCardContent(
                        card = card,
                        revealed = revealed,
                        onReveal = viewModel::reveal,
                        onOpenSource = { viewModel.openSource(card) }
                    )

                    // Rating buttons — always shown after reveal
                    if (revealed) {
                        RatingSection(onRate = viewModel::rate)
                    }

                    // Delete button
                    TextButton(
                        onClick = { cardToDelete = card },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            "Delete this card",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    cardToDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardToDelete = null },
            title = { Text("Delete card?") },
            text = { Text("This will permanently remove this review card. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCard(card.id)
                        cardToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { cardToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CardHeader(
    card: LearningCardEntity,
    titles: Map<String, String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(titles[card.bookId] ?: "Book", style = MaterialTheme.typography.titleMedium)
        Text(
            card.chapterTitle ?: "Chapter ${card.chapterIndex + 1}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("${card.masteryLabel()} · ${card.reviewCount} reviews", style = MaterialTheme.typography.labelMedium)
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
            "Open a book and tap Options → Explain what you learned to practice understanding concepts in your own words.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
