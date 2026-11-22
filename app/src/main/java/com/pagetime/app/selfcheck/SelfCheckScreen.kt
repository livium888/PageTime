package com.pagetime.app.selfcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One button that runs every scenario on this phone and shows what passed.
 * See [SelfCheck] for what it can and cannot tell you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfCheckScreen(onBack: () -> Unit, vm: SelfCheckViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val results = vm.scenarios.mapNotNull { state.results[it.id] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Self-check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "Runs the scenarios we have already talked about, on this phone. It checks rules and " +
                        "wiring: it cannot tell how anything looks or feels. Nothing here changes your " +
                        "library or settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::runAll, enabled = state.running == null) {
                        Text(if (state.running != null) "Running…" else if (state.finished) "Run again" else "Run all")
                    }
                    OutlinedButton(onClick = vm::copyReport, enabled = results.isNotEmpty() && state.running == null) {
                        Text(if (state.copied) "Copied" else "Copy report")
                    }
                }
            }
            if (results.isNotEmpty()) {
                item {
                    Text(SelfCheck.summary(results), style = MaterialTheme.typography.titleSmall)
                }
            }
            var group = ""
            vm.scenarios.forEach { scenario ->
                val header = scenario.group != group
                group = scenario.group
                if (header) {
                    item(key = "g-${scenario.group}") {
                        Text(
                            scenario.group.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                item(key = scenario.id) {
                    ScenarioRow(
                        title = scenario.title,
                        result = state.results[scenario.id],
                        running = state.running == scenario.id,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioRow(title: String, result: SelfCheck.Result?, running: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.size(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                result == null -> Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val (mark, color) = when (result.outcome.status) {
                        SelfCheck.Status.PASS -> "✓" to Color(0xFF3FA66B)
                        SelfCheck.Status.FAIL -> "✗" to MaterialTheme.colorScheme.error
                        SelfCheck.Status.SKIP -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                        SelfCheck.Status.INFO -> "i" to MaterialTheme.colorScheme.primary
                    }
                    Text(mark, color = color, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            result?.outcome?.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.outcome.status == SelfCheck.Status.FAIL) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
