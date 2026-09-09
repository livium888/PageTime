package com.pagetime.app.ui.screens.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.shelf.ReadingLadders
import com.pagetime.app.data.shelf.ShelfRow
import com.pagetime.app.data.shelf.ShelfRowState
import com.pagetime.app.data.shelf.ShelfRows

/**
 * A reading path, in order, with what the reader owns marked off.
 *
 * WHY THE UNAVAILABLE ONES ARE STILL HERE
 *
 * Dropping the books our catalogues cannot supply would make a shorter list
 * and a dishonest one — the path would look complete while quietly missing
 * whichever books happen to be hard to get. So they stay, dimmed, saying
 * plainly that the reader will have to find them elsewhere. The shelf is a
 * reading list first and a download queue second.
 *
 * It never says why a book is missing. That would be a claim about copyright,
 * which varies by country and by year and is not ours to assert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    viewModel: ShelfViewModel = viewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val readCount by viewModel.readCount.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ReadingLadders.GREAT_BOOKS_NAME) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            ReadingLadders.GREAT_BOOKS_NOTE,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "$readCount of ${rows.size} read",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (rows.isEmpty()) 0f else readCount.toFloat() / rows.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (checking) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Checking which of these the libraries have…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                        }
                    }
                }
            }

            items(rows, key = { it.slotId }) { row ->
                // A stage heading is drawn by the first row that belongs to it,
                // so the grouping cannot disagree with the order.
                val isFirstOfStage = rows.firstOrNull { it.stageLabel == row.stageLabel } === row
                if (isFirstOfStage && row.stageLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        row.stageLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LadderRow(
                    row = row,
                    downloading = row.slotId in downloading,
                    onDownload = { viewModel.download(row) },
                    onOpen = { (row.state as? ShelfRowState.Owned)?.let { onOpenBook(it.bookId) } },
                )
            }
        }
    }
}

@Composable
private fun LadderRow(
    row: ShelfRow,
    downloading: Boolean,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val dimmed = ShelfRows.isDimmed(row.state)
    val titleColour =
        if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "${row.position + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColour,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.note?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val state = row.state) {
                is ShelfRowState.Owned -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ShelfRowState.SourceElsewhere -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ShelfRows.sourceElsewhereNote(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Unit
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            downloading -> CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            row.state is ShelfRowState.Owned -> TextButton(onClick = onOpen) { Text("Read") }
            row.state is ShelfRowState.Downloadable -> TextButton(onClick = onDownload) { Text("Get") }
            else -> Unit
        }
    }
}
