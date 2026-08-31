package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedAppsScreen(
    onBack: () -> Unit,
    viewModel: BlockedAppsViewModel = viewModel()
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val blockedPackages by viewModel.blockedPackages.collectAsStateWithLifecycle()
    val quickDisableUntil by viewModel.quickDisableUntil.collectAsStateWithLifecycle()
    val hardLockUntil by viewModel.hardLockUntil.collectAsStateWithLifecycle()

    // A lightweight wall-clock ticker so the countdowns stay live and the switches
    // unlock the moment the hard lock expires — without any persisted-state churn.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val hardRemaining = (hardLockUntil - now).coerceAtLeast(0L)
    val hardLockActive = hardRemaining > 0
    val quickRemaining = if (!hardLockActive) (quickDisableUntil - now).coerceAtLeast(0L) else 0L
    val quickActive = quickRemaining > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // Everything — intro, override controls, and the app list — lives inside one
        // scrolling column so the app list uses the whole screen height and scrolls
        // away together with the header instead of being pinned to a sliver.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    "While your balance is empty, opening one of these apps sends you back to the reader.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            item {
                BlockOverrideControls(
                    quickActive = quickActive,
                    quickRemaining = quickRemaining,
                    hardLockActive = hardLockActive,
                    hardRemaining = hardRemaining,
                    onQuickDisable = viewModel::quickDisable,
                    onCancelQuickDisable = viewModel::cancelQuickDisable,
                    onHardLock = viewModel::hardLock
                )
            }
            items(installed, key = { it.packageName }) { app ->
                val blocked = app.packageName in blockedPackages
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        app.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Switch(
                        checked = blocked,
                        onCheckedChange = { viewModel.toggle(app, it) },
                        // A hard lock is meant to be unavoidable: while it is
                        // active the per-app toggles are frozen too.
                        enabled = !hardLockActive
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BlockOverrideControls(
    quickActive: Boolean,
    quickRemaining: Long,
    hardLockActive: Boolean,
    hardRemaining: Long,
    onQuickDisable: (Long) -> Unit,
    onCancelQuickDisable: () -> Unit,
    onHardLock: (Long) -> Unit
) {
    var manualHours by remember { mutableStateOf("") }
    val manualMinutes = manualHours.toDoubleOrNull()?.times(60)?.roundToInt()?.coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Block override", style = MaterialTheme.typography.titleMedium)

            Text("Quick disable", style = MaterialTheme.typography.titleSmall)
            Text(
                "Temporarily lift the block so you can handle something — the reader won't interrupt you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onQuickDisable(5) },
                    enabled = !hardLockActive && !quickActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("5 min")
                }
                OutlinedButton(
                    onClick = { onQuickDisable(10) },
                    enabled = !hardLockActive && !quickActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("10 min")
                }
            }
            if (quickActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Blocking paused — ${formatRemaining(quickRemaining)} left",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelQuickDisable) { Text("Resume") }
                }
            }

            HorizontalDivider()

            Text("Hard lock", style = MaterialTheme.typography.titleSmall)
            Text(
                "Commit to the block for a fixed time. Once locked it cannot be cancelled or\n" +
                    "lifted — not by the quick-disable above, not by these toggles — no matter what.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onHardLock(30) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("30 min")
                }
                FilledTonalButton(
                    onClick = { onHardLock(60) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1 hr")
                }
                FilledTonalButton(
                    onClick = { onHardLock(120) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2 hr")
                }
            }
            if (hardLockActive) {
                Text(
                    "Locked — ${formatRemaining(hardRemaining)} remaining. The block\n" +
                        "cannot be lifted until this runs out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                HorizontalDivider()

                Text(
                    "Custom duration",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualHours,
                        onValueChange = { manualHours = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Hours") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = !hardLockActive,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { manualMinutes?.let { onHardLock(it.toLong()) } },
                        enabled = !hardLockActive && manualMinutes != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (manualMinutes != null && manualMinutes >= 60) {
                            "Lock ${manualMinutes / 60.0} hr"
                        } else {
                            "Lock ${manualMinutes ?: 0} min"
                        })
                    }
                }
                Text(
                    "Set any hard-lock length in hours (e.g. 1, 1.5, or 3). It still cannot be cancelled early.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes} min"
    } else {
        "${seconds}s"
    }
}
