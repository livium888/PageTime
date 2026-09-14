package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppPrimaryButton
import kotlinx.coroutines.delay

/**
 * Sites off limits by address.
 *
 * WHY THIS IS A SEPARATE SCREEN FROM BLOCKED APPS
 *
 * They look alike and answer different questions. A blocked app is a wall
 * around a whole program whose height depends on how much the reader has read;
 * a blocked site is a line drawn through one address that does not move. Putting
 * them on one list would mean one screen with two sets of rules on it, and the
 * header would have to explain which rows the reading clock applies to.
 *
 * The one thing they do share is the fence on removal — see [sites] below — and
 * that is shared deliberately, because a rule that is cheap to undo is not a
 * rule in either place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedSitesScreen(
    onBack: () -> Unit,
    viewModel: BlockedSitesViewModel = viewModel()
) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val hardLockUntil by viewModel.hardLockUntil.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val refused by viewModel.refused.collectAsStateWithLifecycle()

    // The switches and delete buttons unlock the moment a hard lock expires,
    // which is a moment nothing in the database signals.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val hardLockActive = hardLockUntil > now

    // The same fence the app list uses, and for the same reason: removing a site
    // is how you get back into it, so it costs what entry costs. Adding is never
    // restricted — no one has ever needed to be talked out of more blocking.
    val canRemove = gate.canRemoveBlockedApps && !hardLockActive

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked sites") },
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "A site here is off limits in every browser, whether or not you " +
                        "have time to spend. Block a whole site with bbc.co.uk, or one " +
                        "section of it with bbc.co.uk/news.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                AddSiteCard(
                    draft = draft,
                    message = message,
                    refused = refused,
                    onEdit = viewModel::edit,
                    onAdd = viewModel::add
                )
            }

            if (sites.isEmpty()) {
                item {
                    Text(
                        "No sites yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(sites, key = { it.id }) { site ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                site.id,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (site.pathPrefix == null) {
                                    "Whole site, including subdomains"
                                } else {
                                    "This section and everything under it"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.remove(site) },
                            enabled = canRemove
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove ${site.id}"
                            )
                        }
                    }
                    HorizontalDivider()
                }

                if (!canRemove) {
                    item {
                        Text(
                            if (hardLockActive) {
                                "A hard lock is running, so nothing can be taken off " +
                                    "this list until it ends."
                            } else {
                                "Sites can be added any time, but only removed during a " +
                                    "session. Removing one is how you get back into it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSiteCard(
    draft: String,
    message: String?,
    refused: Boolean,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit
) {
    AppCard(modifier = Modifier.padding(top = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onEdit,
                label = { Text("Site") },
                placeholder = { Text("bbc.co.uk or bbc.co.uk/news") },
                singleLine = true,
                isError = refused,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            AppPrimaryButton(
                text = "Add site",
                onClick = onAdd,
                // Blank is not an error worth a message; it is just nothing to
                // do. The refusal above stays reachable for input that looks
                // like an attempt.
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
