package com.pagetime.app.ui.screens.bookshelf

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Every shelf in one unit, with the books drawn rather than listed.
 *
 * This changes nothing about what the app does. It is the same rows the list
 * screens show, drawn as objects — and a bookshelf is most of what makes a
 * reading app feel like it belongs to the person using it.
 *
 * The colours come out of the titles and authors themselves, so the shelf is
 * different for every reader and identical for the same reader on any device,
 * with nothing stored and no migration needed to change how it looks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    viewModel: BookshelfViewModel = viewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookshelf") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        if (sections.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing on the shelves yet. Import a book, or open the ladder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sections, key = { it.title }) { section ->
                Shelf(section = section, onOpenBook = onOpenBook)
            }
        }
    }
}

@Composable
private fun Shelf(
    section: BookshelfSection,
    onOpenBook: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            section.subtitle?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Books stand ON the plank, so the row is bottom-aligned and the plank
        // follows immediately with no gap. Any space between the two and they
        // read as floating.
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(SHELF_HEIGHT),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(section.books, key = { it.key }) { book ->
                BookSpine(
                    title = book.title,
                    author = book.author,
                    owned = book.owned,
                    progress = book.progress,
                    shelfHeight = SHELF_HEIGHT,
                    onClick = { book.bookId?.let(onOpenBook) },
                )
            }
        }

        Plank()
    }
}

/**
 * The shelf itself.
 *
 * A flat brown bar reads as a floor tile. What sells it is the top face being
 * lighter than the front edge, and a hard dark line where the two meet — the
 * same trick as the spines, one plane catching light and another not.
 */
@Composable
private fun Plank() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF8A6A4A), Color(0xFF6E5238)),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF4A3626))
        )
    }
}

/** Tall enough for a title to be readable down the spine. */
private val SHELF_HEIGHT = 132.dp
