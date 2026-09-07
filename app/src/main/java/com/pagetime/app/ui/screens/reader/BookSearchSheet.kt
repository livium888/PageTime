package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.embed.BookSearchHit

/**
 * Asking a book a question, and building the index that lets it answer.
 *
 * BOTH THINGS IN ONE PLACE, ON PURPOSE
 *
 * Indexing could have lived in the library or in settings, where it would be a
 * chore with no visible reward — a reader would meet a button offering to
 * spend several minutes and several megabytes on a benefit they have never
 * seen. Here it is the answer to a question they have just asked, which is the
 * only moment it is worth anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchSheet(
    state: BookSearchState,
    chapterTitle: (Int) -> String,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onIndex: () -> Unit,
    onStopIndexing: () -> Unit,
    onDeleteIndex: () -> Unit,
    onOpen: (BookSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Search this book", style = MaterialTheme.typography.titleLarge)

            when {
                !state.modelInstalled -> Text(
                    "Searching by meaning needs the retrieval model, which is a " +
                        "one-time 23 MB download in Settings. It stays on the phone " +
                        "and nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.chaptersTotal <= 0 -> Text(
                    "This book could not be read for indexing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    if (state.searchable) {
                        SearchField(
                            query = state.query,
                            onQueryChanged = onQueryChanged,
                            onSearch = onSearch,
                        )
                    }
                    IndexSection(
                        state = state,
                        onIndex = onIndex,
                        onStopIndexing = onStopIndexing,
                        onDeleteIndex = onDeleteIndex,
                    )
                }
            }
        }

        if (state.searchable) {
            Results(
                state = state,
                chapterTitle = chapterTitle,
                onOpen = onOpen,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("What are you looking for?") },
        placeholder = { Text("an idea, not the exact words") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun IndexSection(
    state: BookSearchState,
    onIndex: () -> Unit,
    onStopIndexing: () -> Unit,
    onDeleteIndex: () -> Unit,
) {
    when {
        state.indexing -> {
            val fraction =
                if (state.chaptersTotal > 0) {
                    state.chaptersIndexed.toFloat() / state.chaptersTotal
                } else {
                    0f
                }
            Text(
                "Reading the book: ${state.chaptersIndexed} of ${state.chaptersTotal} chapters.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "You can keep reading; leaving the book pauses this and it " +
                    "carries on from the same chapter next time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onStopIndexing) { Text("Pause") }
        }

        !state.anythingIndexed -> {
            Text(
                "This book has not been indexed yet. Indexing reads it once, " +
                    "on the phone, so you can then find a passage by what it " +
                    "means rather than by the words you remember. It takes a " +
                    "few minutes and ${BookSearchState.TYPICAL_SIZE} of storage, " +
                    "and you can delete it again at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onIndex, modifier = Modifier.fillMaxWidth()) {
                Text("Make this book searchable")
            }
        }

        !state.complete -> {
            // A partial index answers questions about the part it has read, and
            // saying so is better than silently searching a third of a book.
            Text(
                "Indexed to chapter ${state.chaptersIndexed} of ${state.chaptersTotal}. " +
                    "Searching only looks at that much for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onIndex) { Text("Finish indexing") }
                OutlinedButton(onClick = onDeleteIndex) { Text("Delete index") }
            }
        }

        else -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "The whole book is searchable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDeleteIndex) { Text("Delete index") }
            }
        }
    }
}

@Composable
private fun Results(
    state: BookSearchState,
    chapterTitle: (Int) -> String,
    onOpen: (BookSearchHit) -> Unit,
) {
    if (state.searching) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.width(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Looking…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    if (state.answered && state.results.isEmpty()) {
        Text(
            "Nothing in this book is about that.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    ) {
        items(state.results, key = { "${it.chapterIndex}:${it.ordinal}" }) { hit ->
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(hit) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    chapterTitle(hit.chapterIndex),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    hit.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                )
            }
        }
    }
}
