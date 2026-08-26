package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.AiUsageStats
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiUsageScreen(
    onBack: () -> Unit,
    viewModel: AiUsageViewModel = viewModel()
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val level = settings.analysisLevel

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI usage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("Your current plan", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        level.label,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Automatic analysis is attempted ${level.description.lowercase()}. Each checkpoint can make one card request and one concept-map request when Gemini is configured.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            UsageSummaryCard(
                title = "Today",
                calls = stats.todayCalls,
                inputTokens = stats.todayEstimatedInputTokens,
                cards = stats.todayCardsGenerated
            )
            UsageSummaryCard(
                title = "All time",
                calls = stats.totalCalls,
                inputTokens = stats.estimatedInputTokens,
                cards = stats.cardsGenerated
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Gemini has produced", style = MaterialTheme.typography.titleMedium)
                    UsageRow("Successful requests", stats.successfulCalls.toString())
                    UsageRow("Failed requests", stats.failedCalls.toString())
                    UsageRow("Card analyses", stats.cardCalls.toString())
                    UsageRow("Concept-map analyses", stats.conceptCalls.toString())
                    UsageRow("Cards generated", stats.cardsGenerated.toString())
                    UsageRow("Concepts found", stats.conceptsFound.toString())
                    UsageRow("Relationships found", stats.relationshipsFound.toString())
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Input sent", style = MaterialTheme.typography.titleMedium)
                    UsageRow("Today", "${formatNumber(stats.todayInputCharacters)} characters")
                    UsageRow("All time", "${formatNumber(stats.inputCharacters)} characters")
                    UsageRow("Estimated tokens", formatNumber(stats.estimatedInputTokens))
                    Text(
                        "Token counts are estimates based on roughly four characters per token. The dashboard records request metadata, not your book text or API key.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (stats.lastCallAt != null) {
                Text(
                    "Last analysis: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stats.lastCallAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "No Gemini content requests recorded yet. Offline cards work without using the API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsageSummaryCard(title: String, calls: Int, inputTokens: Long, cards: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                UsageMetric(calls.toString(), "API calls")
                UsageMetric(formatNumber(inputTokens), "est. input tokens")
                UsageMetric(cards.toString(), "cards")
            }
        }
    }
}

@Composable
private fun UsageMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatNumber(value: Long): String = "%,d".format(value)
