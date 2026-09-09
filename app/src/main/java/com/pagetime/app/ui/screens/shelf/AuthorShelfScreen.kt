package com.pagetime.app.ui.screens.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.shelf.AuthorLookup
import com.pagetime.app.data.shelf.ShelfRowState

/**
 * Everything one author wrote, next to what the reader has of it.
 *
 * WHY THIS IS NOT THE LADDER
 *
 * The ladder answers "what should anyone read". This answers "you clearly
 * like this person — they wrote twenty more", which is often the better
 * reason to pick up a book and is not something a canon can tell you.
 *
 * MOST OF IT WILL NOT BE DOWNLOADABLE, AND THAT IS FINE
 *
 * The catalogues here serve public-domain books; a living author's
 * bibliography will be almost entirely unavailable. Showing it anyway is the
 * point — knowing the twenty exist is the useful part, and the reader can
 * find them wherever they buy books. Every row says plainly which it is.
 *
 * The list comes from Open Library rather than from a language model, because
 * a bibliography is a set of claims about what exists and a model would
 * cheerfully invent the twenty-first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorShelfScreen(
    authorName: String,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as PageTimeApp
    val viewModel: AuthorShelfViewModel = viewModel(
        factory = AuthorShelfViewModel.Factory(app, authorName),
        key = authorName,
    )
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val owned by viewModel.ownedCount.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(authorName) },
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
                        when {
                            loading -> Text(
                                "Looking up what $authorName published…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            lookup is AuthorLookup.NotFound -> Text(
                                "No record of this author in Open Library. That is a gap in " +
                                    "the catalogue rather than a judgement about the writer.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // Naming the problem beats showing one of them: a
                            // bibliography under the wrong person's name is a
                            // lie the reader has no way to catch.
                            lookup is AuthorLookup.Ambiguous -> Text(
                                "More than one writer is filed under this name, and none of " +
                                    "them clearly the one you are reading. Showing a list " +
                                    "would probably be someone else's.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            rows.isNotEmpty() -> {
                                Text(
                                    "You have $owned of ${rows.size} books listed here.",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (rows.isEmpty()) 0f else owned.toFloat() / rows.size },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "From Open Library. Most of a modern writer's work will " +
                                        "not be here to download — knowing it exists is the " +
                                        "useful part.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            items(rows, key = { it.slotId }) { row ->
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
