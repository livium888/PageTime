package com.pagetime.app.ui.screens.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pagetime.app.data.recommend.OpenLibraryPicks
import com.pagetime.app.data.recommend.ReaderPicks

private sealed interface AboutLoad {
    data object Loading : AboutLoad
    data object Unreachable : AboutLoad
    data class Loaded(val about: ReaderPicks.About) : AboutLoad
}

/**
 * About one recommended book: its cover, what it is about and what it is
 * filed under, as Open Library describes it.
 *
 * The title, author and rating are already known and show at once; the rest
 * is asked for when the sheet opens. A book the catalogue has no description
 * of says so, rather than showing an empty page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDetailSheet(
    pick: ReaderPicks.Pick,
    /** Searches the free catalogues for this title; null where that is not on offer. */
    onFind: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val load by produceState<AboutLoad>(AboutLoad.Loading, pick.key) {
        value = OpenLibraryPicks().about(pick.key)?.let { AboutLoad.Loaded(it) } ?: AboutLoad.Unreachable
    }
    val about = (load as? AboutLoad.Loaded)?.about

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
            Row(verticalAlignment = Alignment.Top) {
                about?.coverUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(84.dp)
                            .height(126.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pick.title, style = MaterialTheme.typography.titleLarge)
                    if (pick.byLine.isNotBlank()) {
                        Text(
                            pick.byLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(pick.ratingLine, style = MaterialTheme.typography.bodyMedium)
                }
            }

            when (load) {
                AboutLoad.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Looking it up…")
                }
                AboutLoad.Unreachable -> Text(
                    "Open Library didn't answer, so there is no description to show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is AboutLoad.Loaded -> {
                    Text(
                        about?.description ?: "Open Library has no description of this book.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (about?.description != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!about?.subjects.isNullOrEmpty()) {
                        Text(
                            "Filed under: " + about!!.subjects.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onFind != null) {
                Button(onClick = onFind, modifier = Modifier.fillMaxWidth()) { Text("Look for a free copy") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
