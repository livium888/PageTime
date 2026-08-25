package com.pagetime.app.ui.screens.review

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
            ReviewCard(
                card = cards.first(),
                bookTitle = titles[cards.first().bookId] ?: "Book",
                revealed = revealed,
                onReveal = viewModel::reveal,
                onRate = viewModel::rate,
                onOpenSource = { viewModel.openSource(cards.first()) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
                }
            }
        }
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
            "Keep reading for about a minute, then use Reader → Options → Generate cards now. New cards appear here immediately.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ReviewCard(
    card: LearningCardEntity,
    bookTitle: String,
    revealed: Boolean,
    onReveal: () -> Unit,
    onRate: (LearningRating) -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Active recall", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(bookTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            card.chapterTitle ?: "Chapter ${card.chapterIndex + 1}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("${card.masteryLabel()} · ${card.reviewCount} reviews", style = MaterialTheme.typography.labelMedium)
        if (card.generatedByAi) {
            Text(
                "AI topic: ${card.topic ?: "Important idea"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            Text(
                "Offline recall card from this passage",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        card.sourceQuote?.let { quote ->
            Text(
                "Source: \"$quote\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (card.sourceLocator != null || card.sourceFraction != null) {
            OutlinedButton(onClick = onOpenSource) { Text("Open source passage") }
        } else {
            Text(
                "No source position saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
                } else {
                    Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("Reveal answer") }
                }
            }
        }

        if (revealed) {
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
}
