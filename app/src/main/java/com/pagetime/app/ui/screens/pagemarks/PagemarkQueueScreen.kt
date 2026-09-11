package com.pagetime.app.ui.screens.pagemarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.PagemarkSession
import com.pagetime.app.data.local.PagemarkEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagemarkQueueScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    viewModel: PagemarkQueueViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Reading queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.rows.isEmpty() -> EmptyQueue(
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.dueCount > 0) {
                    item {
                        Text(
                            "${state.dueCount} ${if (state.dueCount == 1) "chunk is" else "chunks are"} due for re-reading",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                items(state.rows, key = { it.pagemark.id }) { row ->
                    PagemarkCard(
                        row = row,
                        onOpen = {
                            viewModel.open(row.pagemark) {
                                onOpenBook(row.pagemark.bookId)
                            }
                        },
                        onRaise = { viewModel.raisePriority(row.pagemark) },
                        onLower = { viewModel.lowerPriority(row.pagemark) },
                        onDelete = { viewModel.delete(row.pagemark) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "No chunks yet",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Incremental reading reads a book in chunks instead of a single " +
                "relentless pass. From the reader's Options menu, pick \u201cStart " +
                "chunk here\u201d when you pause somewhere; finish a chunk and say " +
                "how it went, and PageTime schedules that passage to come back " +
                "when it is worth re-reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PagemarkCard(
    row: PagemarkRow,
    onOpen: () -> Unit,
    onRaise: () -> Unit,
    onLower: () -> Unit,
    onDelete: () -> Unit
) {
    val chunk = row.pagemark
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        chunk.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.bookTitle} · ${stateLabel(chunk)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete chunk",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) {
                    Text(if (PagemarkSession.stateOf(chunk) == PagemarkSession.State.READING) "Continue" else "Read chunk")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "P${chunk.priority}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onLower, enabled = chunk.priority > PagemarkSession.MIN_PRIORITY) {
                    Icon(Icons.Filled.Remove, contentDescription = "Lower priority")
                }
                IconButton(onClick = onRaise, enabled = chunk.priority < PagemarkSession.MAX_PRIORITY) {
                    Icon(Icons.Filled.Add, contentDescription = "Raise priority")
                }
            }
        }
    }
}

private fun stateLabel(chunk: PagemarkEntity): String = when (PagemarkSession.stateOf(chunk)) {
    PagemarkSession.State.READING -> "Reading now"
    PagemarkSession.State.QUEUED -> "Queued"
    PagemarkSession.State.SUSPENDED -> "Suspended"
    PagemarkSession.State.DONE -> when (val due = chunk.dueAt) {
        null -> "Scheduled"
        else -> if (due <= System.currentTimeMillis()) "Due for re-reading" else "Scheduled"
    }
}