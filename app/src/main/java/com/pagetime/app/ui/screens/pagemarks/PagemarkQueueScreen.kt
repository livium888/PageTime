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
                "relentless pass. Open a book, choose \u201cStart a chunk here\u201d " +
                "once, and read. When you stop, tap Finish on the chunk bar at " +
                "the foot of the page and say how it went \u2014 that passage comes " +
                "back for re-reading when it is worth re-reading, and the next " +
                "chunk is already waiting where you stopped. Every chunk that " +
                "comes back shows up here. When a chunk has given you everything " +
                "it has, retire it from the Finish dialog and it stops coming back \u2014 " +
                "retired chunks stay here, last, as the record of what you have " +
                "finished with.",
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
                    // The span, because a chunk's start and end are otherwise
                    // nowhere on the screen: the reader could not tell what
                    // part of the book "Chunk 3" even is.
                    Text(
                        "${row.bookTitle} · " +
                            PagemarkSession.spanLabel(chunk.startFraction, chunk.endFraction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    val now = System.currentTimeMillis()
                    val label = stateLabel(chunk, now)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stateIsDue(chunk, now)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
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
            if (PagemarkSession.stateOf(chunk) == PagemarkSession.State.HARVESTED) {
                // A retired chunk is not work, so it gets no button and no
                // priority: there is nothing to open and nothing to order. Its
                // span stays so the reader can see what they finished with, and
                // the delete control above stays so they can let even that go.
                Text(
                    "Finished with \u2014 what you kept from it is in your cards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpen) {
                        Text(
                            if (PagemarkSession.stateOf(chunk) == PagemarkSession.State.READING) {
                                "Continue"
                            } else {
                                "Read chunk"
                            }
                        )
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
}

/**
 * What state a chunk is in, in words the reader can act on.
 *
 * "Scheduled" was the whole of what a finished chunk used to say, which is
 * true and useless: the schedule is the point of the feature, so it is stated
 * as a time — when this comes back — rather than as a state name.
 */
private fun stateLabel(chunk: PagemarkEntity, now: Long): String = when (
    PagemarkSession.stateOf(chunk)
) {
    PagemarkSession.State.READING -> "Reading now"
    PagemarkSession.State.QUEUED -> "Not started yet"
    PagemarkSession.State.SUSPENDED -> "Paused"
    PagemarkSession.State.DONE -> PagemarkSession.dueLabel(chunk.dueAt, now)
    PagemarkSession.State.HARVESTED -> "Retired \u2014 nothing more to keep"
}

private fun stateIsDue(chunk: PagemarkEntity, now: Long): Boolean =
    PagemarkSession.stateOf(chunk) == PagemarkSession.State.READING ||
        (chunk.dueAt != null && chunk.dueAt <= now)