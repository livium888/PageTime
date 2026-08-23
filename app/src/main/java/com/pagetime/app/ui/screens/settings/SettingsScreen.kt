package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.ui.formatMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageBlockedApps: () -> Unit,
    onPermissions: () -> Unit,
    onUsageAudit: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val balanceSeconds by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsStateWithLifecycle()
    val ratio by viewModel.ratio.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your time", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Browse balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatMinutes(balanceSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total reading", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMinutes(totalReadingSeconds), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Reading rate", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1 minute of reading earns ${"%.1f".format(ratio)} minutes of browsing",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = ratio.toFloat(),
                        onValueChange = { viewModel.setRatio(it.toDouble()) },
                        valueRange = 0.5f..3.0f,
                        steps = 4
                    )
                }
            }

            OutlinedButton(
                onClick = onManageBlockedApps,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Block, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Manage blocked apps")
            }

            OutlinedButton(
                onClick = onUsageAudit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Usage history & protection")
            }

            OutlinedButton(
                onClick = onPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.AccessibilityNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Permissions & setup")
            }
        }
    }
}
