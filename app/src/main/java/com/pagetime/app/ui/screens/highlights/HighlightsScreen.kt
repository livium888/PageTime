package com.pagetime.app.ui.screens.highlights

import android.app.Application
import android.text.format.DateUtils
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Highlight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Everything the reader marked in one book.
 *
 * The marks were already being painted in both readers; this is the list that
 * makes them usable — each one shows the quoted text and how far in it sits,
 * opens the book at that spot, or can be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HighlightsViewModel = viewModel(
        key = "highlights-$bookId",
        factory = HighlightsViewModelFactory(app, bookId)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Highlights")
                        if (state.bookTitle.isNotBlank()) {
                            Text(
                                state.bookTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
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

            state.rows.isEmpty() -> EmptyHighlights(
                isTextBook = state.isTextBook,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.rows, key = { it.highlight.id }) { row ->
                    HighlightCard(
                        row = row,
                        onOpen = {
                            vm.open(row.highlight) { onOpenBook(row.highlight.bookId) }
                        },
                        onDelete = { vm.delete(row.highlight) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHighlights(isTextBook: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Highlight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("Nothing marked yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isTextBook) {
                "Highlighting works by anchoring, because a paged reader has no " +
                    "drag selection to carry across a page: open Options on the page " +
                    "where a passage starts and pick \u201cStart highlight here\u201d, " +
                    "turn to where it ends and pick \u201cEnd highlight here\u201d. " +
                    "The span can cover as many pages as you like, and it comes " +
                    "back here."
            } else {
                "Select text in the book the way you normally would, then pick " +
                    "\u201cSave highlight\u201d from the menu that appears. Highlights " +
                    "keep their colour across page turns and collect here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HighlightCard(
    row: HighlightRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val highlight = row.highlight
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "\u201c${highlight.quote}\u201d",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6
            )
            Spacer(Modifier.height(8.dp))
            val meta = buildList {
                row.whereLabel?.let { add(it) }
                add(
                    DateUtils.getRelativeTimeSpanString(highlight.createdAt)
                        .toString()
                )
            }.joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text("Open in book") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete highlight",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
