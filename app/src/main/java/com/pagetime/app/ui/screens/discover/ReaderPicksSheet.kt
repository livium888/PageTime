package com.pagetime.app.ui.screens.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.BookGenre
import com.pagetime.app.data.recommend.ReaderPicks

/** What the picks sheet is showing. */
data class ReaderPicksState(
    val genres: List<BookGenre> = emptyList(),
    val genre: BookGenre? = null,
    val loading: Boolean = false,
    val picks: List<ReaderPicks.Pick> = emptyList(),
    /** The catalogue could not be asked. */
    val unreachable: Boolean = false,
)

/**
 * Books other readers rate highly, by genre, with the reader's own genres first.
 *
 * A pick is a suggestion of what to read, not a download: most well-rated
 * books are in copyright. "Look for a free copy" searches the app's free
 * catalogues for the title, and says nothing was found when nothing is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPicksSheet(
    state: ReaderPicksState,
    onGenre: (BookGenre) -> Unit,
    onFind: (ReaderPicks.Pick) -> Unit,
    onDismiss: () -> Unit,
) {
    // The book the reader tapped, opened over this list.
    var about by remember { mutableStateOf<ReaderPicks.Pick?>(null) }
    about?.let { pick ->
        PickDetailSheet(
            pick = pick,
            onFind = {
                about = null
                onFind(pick)
            },
            onDismiss = { about = null },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("What readers rate highly", style = MaterialTheme.typography.titleLarge)
            Text(
                "Ratings from Open Library's readers. Your own genres come first; books you " +
                    "already have are left out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.genres.forEach { genre ->
                    FilterChip(
                        selected = genre == state.genre,
                        onClick = { onGenre(genre) },
                        label = { Text(genre.label) },
                    )
                }
            }
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Asking Open Library…")
                }
                state.unreachable -> Text(
                    "Open Library didn't answer. Check your connection and tap the genre again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                state.picks.isEmpty() -> Text(
                    "Nothing with enough ratings to recommend in this genre.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> state.picks.forEach { pick ->
                    Card(onClick = { about = pick }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp)) {
                            Text(pick.title, style = MaterialTheme.typography.titleMedium)
                            if (pick.byLine.isNotBlank()) {
                                Text(
                                    pick.byLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(pick.ratingLine, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { about = pick }) { Text("About this book") }
                                TextButton(onClick = { onFind(pick) }) { Text("Look for a free copy") }
                            }
                        }
                    }
                }
            }
        }
    }
}
