package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Which installed apps count as "reading" for
 * [com.pagetime.app.data.usage.ExternalReadingTracker] — off for every app
 * until the reader turns one on here, the same "nothing trusted by default"
 * posture as the toggle in Settings that gates the feature as a whole.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalReadingAppsScreen(
    onBack: () -> Unit,
    viewModel: ExternalReadingAppsViewModel = viewModel()
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val trustedPackages by viewModel.trustedPackages.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("External reading apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    "Time an app checked here spends in the foreground with the screen on " +
                        "counts toward reading credit, at half rate and capped per day — see " +
                        "the description on the toggle in Settings for why. Nothing is trusted " +
                        "until you turn it on here, even with that toggle already on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            items(installed, key = { it.packageName }) { app ->
                val trusted = app.packageName in trustedPackages
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
                        checked = trusted,
                        onCheckedChange = { viewModel.toggle(app, it) }
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
