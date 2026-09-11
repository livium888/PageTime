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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * WHY IT IS A CASE AND NOT A LIST OF ROWS
 *
 * Spines on a transparent background are a diagram of a shelf. What makes the
 * drawn object read as furniture is everything around the books: a back panel
 * in shadow at both ends, the shadow each shelf casts on the one below it, a
 * groove under every plank, and a brass label on the front of the case. None
 * of that is content, and all of it is the difference.
 *
 * Tapping does something for every book, including the ones you do not have:
 * an owned book opens, and a ghost opens the author's shelf, which is where
 * books come from. A book that swallows a tap is worse than no book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    viewModel: BookshelfViewModel = viewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val shelfCase = rememberShelfCase()

    Scaffold(
        containerColor = shelfCase.backBottom,
        topBar = {
            TopAppBar(
                title = { Text("Bookshelf") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = shelfCase.rail,
                    titleContentColor = shelfCase.lettering,
                    navigationIconContentColor = shelfCase.lettering,
                ),
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .caseBack(shelfCase),
        ) {
            if (sections.isEmpty()) {
                EmptyCase(shelfCase = shelfCase, onBack = onBack)
                return@Box
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(sections, key = { it.title }) { section ->
                    Shelf(
                        section = section,
                        shelfCase = shelfCase,
                        onOpenBook = onOpenBook,
                        onOpenAuthor = onOpenAuthor,
                    )
                }
            }
        }
    }
}

/**
 * The inside of the case.
 *
 * Three things, in order: the wood, a little grain so the wood is not a flat
 * fill, and the two uprights in shadow at the ends. The uprights are doing the
 * most work — a rectangle of colour with dark edges reads as a box, and the
 * same rectangle without them reads as a background.
 */
private fun Modifier.caseBack(shelfCase: ShelfCase): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(shelfCase.backTop, shelfCase.backBottom)))

    val grainStep = 26.dp.toPx()
    var y = 18.dp.toPx()
    while (y < size.height) {
        drawRect(
            color = shelfCase.groove.copy(alpha = 0.06f),
            topLeft = Offset(0f, y),
            size = Size(size.width, 1.dp.toPx()),
        )
        y += grainStep
    }

    val upright = 30.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(shelfCase.contactShadow.copy(alpha = 0.55f), Color.Transparent),
            startX = 0f,
            endX = upright,
        ),
        size = Size(upright, size.height),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, shelfCase.contactShadow.copy(alpha = 0.55f)),
            startX = size.width - upright,
            endX = size.width,
        ),
        topLeft = Offset(size.width - upright, 0f),
        size = Size(upright, size.height),
    )

    // The cornice the app bar sits on.
    drawRect(
        color = shelfCase.groove,
        size = Size(size.width, 3.dp.toPx()),
    )
    drawRect(
        color = shelfCase.plankTop.copy(alpha = 0.45f),
        topLeft = Offset(0f, 3.dp.toPx()),
        size = Size(size.width, 1.dp.toPx()),
    )
}

@Composable
private fun Shelf(
    section: BookshelfSection,
    shelfCase: ShelfCase,
    onOpenBook: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted but only as wide as it needs: a long author's name
            // shortens on the plate rather than pushing the count off it.
            Plaque(
                text = section.title,
                shelfCase = shelfCase,
                emphasis = section.emphasis,
                modifier = Modifier.weight(1f, fill = false),
            )
            section.subtitle?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = shelfCase.letteringDim,
                )
            }
        }

        // Books stand ON the plank, so the row is bottom-aligned and the plank
        // follows immediately with no gap. Any space between the two and they
        // read as floating.
        Box(
            Modifier
                .fillMaxWidth()
                .height(SHELF_HEIGHT)
                .drawBehind {
                    // What the shelf above casts onto the back of this one, and
                    // the shadow the books sit in where they meet the plank.
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                shelfCase.contactShadow.copy(alpha = 0.42f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = 22.dp.toPx(),
                        ),
                    )
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                shelfCase.contactShadow.copy(alpha = 0.50f),
                            ),
                            startY = size.height - 14.dp.toPx(),
                            endY = size.height,
                        ),
                    )
                },
        ) {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
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
                        shelfCase = shelfCase,
                        onClick = {
                            if (book.owned) {
                                book.bookId?.let(onOpenBook)
                            } else {
                                onOpenAuthor(book.author)
                            }
                        },
                    )
                }
            }
        }

        Plank(shelfCase)
    }
}

/**
 * The shelf itself.
 *
 * A flat brown bar reads as a floor tile. What sells it is the top face being
 * lighter than the front edge, a hard dark line where the two meet, and a
 * groove under the lot where it is set into the uprights — the same trick as
 * the spines, one plane catching light and another not.
 */
@Composable
private fun Plank(shelfCase: ShelfCase) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.07f))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(
                    Brush.verticalGradient(listOf(shelfCase.plankTop, shelfCase.plankFace)),
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(shelfCase.plankEdge)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(shelfCase.groove)
        )
    }
}

/**
 * The brass label on the front of a shelf.
 *
 * The shelves used to be told apart by a line of small grey capitals, which
 * made the most important thing on the screen — which shelf am I looking at —
 * the least visible. A label plate puts it on the case, where a label goes.
 *
 * "Reading now" gets the polished plate and everything else the dull one, so
 * the shelf the reader is actually in the middle of is the one they see first.
 */
@Composable
private fun Plaque(
    text: String,
    shelfCase: ShelfCase,
    emphasis: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .shadow(elevation = if (emphasis) 3.dp else 1.dp, shape = shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (emphasis) {
                        listOf(shelfCase.brass, shelfCase.brassDark)
                    } else {
                        listOf(shelfCase.brassDark, shelfCase.brassDark)
                    },
                ),
            )
            .drawBehind {
                // A lit top edge and a dark foot, so the plate is pressed metal.
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.5f,
                    ),
                )
            }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasis) Color(0xFF2A1E10) else shelfCase.lettering,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An empty case, which is a real state and not an error.
 *
 * It shows the furniture the reader is going to fill, with the shelf there and
 * nothing on it, and a way back to the thing that fills it.
 */
@Composable
private fun EmptyCase(shelfCase: ShelfCase, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing on the shelves yet",
            style = MaterialTheme.typography.titleLarge,
            color = shelfCase.lettering,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Import a book and it will stand here, with a spine of its own.",
            style = MaterialTheme.typography.bodyMedium,
            color = shelfCase.letteringDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.width(190.dp)) { Plank(shelfCase) }
        Spacer(Modifier.height(28.dp))
        TextButton(onClick = onBack) {
            Text("Back to your library", color = shelfCase.brass)
        }
    }
}

/** Tall enough for a title to be readable down the spine. */
private val SHELF_HEIGHT = 132.dp
