package com.pagetime.app.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.MapMoment
import com.pagetime.app.data.local.isReflowedText
import com.pagetime.app.data.youtube.YouTubeTranscriptFetcher
import com.pagetime.app.ui.AppPrimaryButton
import com.pagetime.app.ui.AppSecondaryButton
import com.pagetime.app.ui.Spacing
import com.pagetime.app.ui.formatMinutes

private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    // A PDF is converted to an EPUB on the phone, once, at import — so the
    // picker has to offer it, and importing one is slower than importing a book.
    "application/pdf",
    "text/plain",
    "text/*",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenConcepts: (String) -> Unit,
    onDiscover: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
    /** The curated reading path. */
    onOpenShelf: () -> Unit = {},
    /** Everything this author wrote, whether or not we can serve it. */
    onOpenAuthor: (String) -> Unit = {},
    /** The shelves, drawn as books rather than listed. */
    onOpenBookshelf: () -> Unit = {},
    /** The incremental-reading chunk queue. */
    onOpenPagemarks: () -> Unit = {},
    /** Open a PDF in the native reader (page-by-page, not reflowed). */
    onOpenPdf: (String) -> Unit = {},
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val balanceSeconds by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsStateWithLifecycle()
    val readingStreak by viewModel.readingStreak.collectAsStateWithLifecycle()
    val didReadToday by viewModel.didReadToday.collectAsStateWithLifecycle()
    val showNeverMissTwiceNudge by viewModel.showNeverMissTwiceNudge.collectAsStateWithLifecycle()
    val genreSummary by viewModel.genreSummary.collectAsStateWithLifecycle()
    val untaggedCount by viewModel.untaggedCount.collectAsStateWithLifecycle()
    val pendingGenres by viewModel.pendingGenres.collectAsStateWithLifecycle()
    val genreError by viewModel.genreError.collectAsStateWithLifecycle()
    pendingGenres?.let { pending ->
        com.pagetime.app.ui.components.AskYourAiDialog(
            title = "Sort ${pending.count} book${if (pending.count == 1) "" else "s"} by genre",
            prompt = pending.prompt,
            error = genreError,
            onReply = viewModel::submitGenreReply,
            onDismiss = viewModel::dismissGenres,
        )
    }
    val librarianSuggestion by viewModel.librarianSuggestion.collectAsStateWithLifecycle()
    val upNextBook by viewModel.upNextBook.collectAsStateWithLifecycle()
    val lastMapMoment by viewModel.lastMapMoment.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val pendingFormat by viewModel.pendingFormat.collectAsStateWithLifecycle()
    val formatError by viewModel.formatError.collectAsStateWithLifecycle()
    pendingFormat?.let { pending ->
        com.pagetime.app.ui.components.AskYourAiDialog(
            title = "Tidy the text with your AI",
            prompt = pending.prompt,
            error = formatError,
            onReply = viewModel::submitFormatReply,
            onDismiss = viewModel::dismissFormat,
        )
    }
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val coversMissing by viewModel.coversMissing.collectAsStateWithLifecycle()
    val findingCovers by viewModel.findingCovers.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val todayLine = if (gate.enabled) {
        com.pagetime.app.ui.screens.reader.ReadingToday.label(gate.readToday, gate.target, lockOn = true)
    } else {
        "${gate.readToday / 60} min read · ${formatMinutes(balanceSeconds)} of browsing banked"
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var showPasteTextDialog by remember { mutableStateOf(false) }
    var replaceBook by remember { mutableStateOf<BookEntity?>(null) }
    var pasteBook by remember { mutableStateOf<BookEntity?>(null) }
    var openPdfBook by remember { mutableStateOf<BookEntity?>(null) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val book = replaceBook
        replaceBook = null
        if (uri != null && book != null) viewModel.replaceTranscript(book.id, uri)
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importBook(uri) { imported -> onOpenBook(imported.id) }
    }
    val launchImport = {
        filePicker.launch(IMPORT_MIME_TYPES)
    }
    val launchYouTubeImport = {
        showYouTubeDialog = true
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // A plain line and one button. Everything that adds a book, and the
        // other ways of looking at the shelf, are behind the "+".
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a book, and more")
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (importing) "Importing…" else "Import a file") },
                            enabled = !importing,
                            onClick = { moreMenuExpanded = false; launchImport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Paste text") },
                            enabled = !importing,
                            onClick = { moreMenuExpanded = false; showPasteTextDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("YouTube transcript") },
                            enabled = !importing,
                            onClick = { moreMenuExpanded = false; launchYouTubeImport() }
                        )
                        if (coversMissing > 0 || findingCovers) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (findingCovers) "Looking for covers…"
                                        else "Find covers for $coversMissing book${if (coversMissing == 1) "" else "s"}"
                                    )
                                },
                                enabled = !findingCovers,
                                onClick = { moreMenuExpanded = false; viewModel.findCovers() }
                            )
                        }
                        if (untaggedCount > 0) {
                            DropdownMenuItem(
                                text = {
                                    Text("Sort $untaggedCount book${if (untaggedCount == 1) "" else "s"} by genre")
                                },
                                onClick = { moreMenuExpanded = false; viewModel.sortByGenre() }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Reading queue") },
                            onClick = { moreMenuExpanded = false; onOpenPagemarks() }
                        )
                        DropdownMenuItem(
                            text = { Text("Reading ladder") },
                            onClick = { moreMenuExpanded = false; onOpenShelf() }
                        )
                        DropdownMenuItem(
                            text = { Text("Shelves") },
                            onClick = { moreMenuExpanded = false; onOpenBookshelf() }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.l),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.padding(22.dp).size(44.dp)
                    )
                }
                Spacer(Modifier.height(Spacing.l))
                Text(
                    "A library begins with a single book",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "Download from Discover — or import an EPUB, PDF or plain-text file from your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.l))
                AppPrimaryButton(
                    text = if (importing) "Importing…" else "Import from phone",
                    icon = Icons.Filled.Add,
                    enabled = !importing,
                    onClick = launchImport
                )
                Spacer(Modifier.height(Spacing.s))
                // Two alternatives beside the one action above, not two more
                // things of the same weight. Four identical full-width
                // buttons in a column told the reader nothing about which
                // one to press.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AppSecondaryButton(
                        text = "Discover",
                        modifier = Modifier.weight(1f),
                        onClick = onDiscover
                    )
                    AppSecondaryButton(
                        text = "Ladder",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenShelf
                    )
                }
            }
        } else {
            // THE BOOK YOU ARE IN, THEN YOUR SHELF
            //
            // This screen used to put up to seven different things above the
            // first book: a genre count, a sort button, a streak, a warning
            // about yesterday, and three kinds of suggestion card. The app now
            // opens on the book being read, so this page has one job, choosing
            // a different one. It shows the current book, one line about
            // today, and covers.
            val open: (BookEntity) -> Unit = { book ->
                if (book.format == "pdf") openPdfBook = book else onOpenBook(book.id)
            }
            val actions: @Composable (BookEntity, Boolean, () -> Unit) -> Unit = { book, expanded, dismiss ->
                BookActionsMenu(
                    book = book,
                    expanded = expanded,
                    onDismiss = dismiss,
                    onAuthor = { onOpenAuthor(book.author) },
                    onDelete = { viewModel.delete(book) },
                    onReformat = { viewModel.reformatWithAI(book.id) },
                    onReplace = { replaceBook = book; replacePicker.launch(arrayOf("text/plain", "text/*")) },
                    onPaste = { pasteBook = book },
                    onRestore = { viewModel.restoreTranscript(book.id) },
                    hasRawBackup = viewModel.hasRawBackup(book),
                    onCopy = { viewModel.copyTranscript(book) },
                    onShare = { viewModel.shareTranscript(book) },
                    onOpenPdf = { onOpenPdf(book.id) },
                    onFindCover = { viewModel.findCover(book) },
                )
            }
            val current = upNextBook
            val shelf = books.filter { it.id != current?.id }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                current?.let { book ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CurrentBookCard(book = book, onOpen = { open(book) }, actions = actions)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Today: " + todayLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                // One shelf for each kind of thing the library holds, each with a
                // name, a colour and a tile shape of its own. A video transcript
                // and an EPUB used to be the same tile in the same grid.
                LibraryShelves.shelves(shelf).forEach { (kind, onShelf) ->
                    val accent = shelfAccent(kind)
                    item(key = "shelf-${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfHeader(kind = kind, count = onShelf.size, accent = accent)
                    }
                    if (kind == BookKind.VIDEO) {
                        // Wide, the shape of the picture itself: two to a row.
                        items(onShelf, key = { it.id }, span = { GridItemSpan(2) }) { book ->
                            VideoTile(book = book, accent = accent, onOpen = { open(book) }, actions = actions)
                        }
                    } else {
                        items(onShelf, key = { it.id }) { book ->
                            BookTile(book = book, accent = accent, onOpen = { open(book) }, actions = actions)
                        }
                    }
                    item(key = "board-${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfBoard(accent)
                    }
                }
            }
        }
    }

    pasteBook?.let { book ->
        PasteTranscriptDialog(
            book = book,
            onPaste = { text ->
                pasteBook = null
                viewModel.replaceTranscript(book.id, text)
            },
            onDismiss = { pasteBook = null }
        )
    }

    if (showPasteTextDialog) {
        PasteTextDialog(
            onImport = { title, text ->
                showPasteTextDialog = false
                viewModel.importPastedText(title, text) { imported -> onOpenBook(imported.id) }
            },
            onDismiss = { showPasteTextDialog = false }
        )
    }

    if (showYouTubeDialog) {
        YouTubeImportDialog(
            onImport = { url ->
                showYouTubeDialog = false
                viewModel.importYouTubeUrl(url) { imported -> onOpenBook(imported.id) }
            },
            onDismiss = { showYouTubeDialog = false }
        )
    }

    // PDF open-choice dialog: read as text (EPUB) or native PDF reader
    openPdfBook?.let { book ->
        AlertDialog(
            onDismissRequest = { openPdfBook = null },
            title = { Text(book.title) },
            text = {
                Text("How would you like to read this PDF?")
            },
            confirmButton = {
                TextButton(onClick = {
                    openPdfBook = null
                    onOpenPdf(book.id)
                }) { Text("Original PDF") }
            },
            dismissButton = {
                TextButton(onClick = {
                    openPdfBook = null
                    onOpenBook(book.id)
                }) { Text("Reflowed text") }
            }
        )
    }
}

/**
 * Text from anywhere, as a book: an article, notes, an AI's answer.
 *
 * The clipboard button is the main path. A long article pasted through the
 * keyboard's own paste works too, but one tap that fills the box is what
 * "copy it over there, read it here" should cost.
 */
@Composable
private fun PasteTextDialog(
    onImport: (title: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val words = remember(text) { text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste any text to read it as a book. Markdown headings, bold, " +
                        "italic and lists are shown as formatting.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    val pasted = clipboard?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                        .orEmpty()
                    if (pasted.isBlank()) {
                        note = "The clipboard is empty. Copy some text first."
                    } else {
                        text = pasted
                        note = null
                    }
                }) { Text("Paste from clipboard") }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    placeholder = { Text("Paste text here…") },
                    minLines = 8,
                    maxLines = 12
                )
                Text(
                    note ?: if (words > 0) "$words words" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(title, text) }, enabled = text.isNotBlank()) {
                Text("Add to library")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PasteTranscriptDialog(
    book: BookEntity,
    onPaste: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste edited transcript") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste your edited text below. It will replace the transcript in this book; the original stays backed up.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    placeholder = { Text("Paste transcript here…") },
                    minLines = 10,
                    maxLines = 14
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPaste(text) }, enabled = text.isNotBlank()) {
                Text("Replace transcript")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun YouTubeImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    val fetcher = remember { YouTubeTranscriptFetcher() }
    val isValidUrl = remember(url) { fetcher.isYouTubeUrl(url) && fetcher.extractVideoId(url) != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import YouTube transcript") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a YouTube video URL to import its transcript as a readable book.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("YouTube URL") },
                    placeholder = { Text("https://youtube.com/watch?v=...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onImport(url) },
                enabled = isValidUrl
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** A cover, or for a book without one a plain panel carrying its title, so pasted texts are still told apart. */
@Composable
private fun BookCover(book: BookEntity, modifier: Modifier = Modifier, titleLines: Int = 4) {
    // Video books show the video's picture, including ones added before covers were kept.
    val cover = com.pagetime.app.data.youtube.VideoOwnership.coverFor(book.id, book.coverUrl)
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The book being read: the one large thing on the page, and the one button. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CurrentBookCard(
    book: BookEntity,
    onOpen: () -> Unit,
    actions: @Composable (BookEntity, Boolean, () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val progress = book.scrollProgress.coerceIn(0f, 1f)
    val left = com.pagetime.app.ui.screens.reader.ReadingTimeLeft.label(book.wordCount, progress)
        ?.removeSuffix(" in book")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(14.dp)) {
            BookCover(book, Modifier.width(92.dp).height(136.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    listOfNotNull("${(progress * 100).toInt()}%", left).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Button(onClick = onOpen) { Text(if (progress > 0f) "Continue" else "Start reading") }
                    if (menu) actions(book, true) { menu = false }
                }
            }
        }
    }
}

/** Each kind's colour: on its shelf's name, its board, and the badge on every tile. */
private fun shelfAccent(kind: BookKind): androidx.compose.ui.graphics.Color = when (kind) {
    BookKind.BOOK -> androidx.compose.ui.graphics.Color(0xFF6FD6C0)
    BookKind.PDF -> androidx.compose.ui.graphics.Color(0xFFE5736B)
    BookKind.VIDEO -> androidx.compose.ui.graphics.Color(0xFFE0A458)
    BookKind.PASTED -> androidx.compose.ui.graphics.Color(0xFF8FA6FF)
}

@Composable
private fun ShelfHeader(kind: BookKind, count: Int, accent: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            kind.shelfTitle.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${kind.note} · $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The edge of the shelf the covers stand on. */
@Composable
private fun ShelfBoard(accent: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(accent.copy(alpha = 0.35f)),
    )
}

/** The few letters that say what a tile is. */
@Composable
private fun KindBadge(book: BookEntity, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        LibraryShelves.badge(book),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = androidx.compose.ui.graphics.Color(0xFF0B0E14),
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(accent)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** A video transcript: its picture at its own shape, with the title beneath. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoTile(
    book: BookEntity,
    accent: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    actions: @Composable (BookEntity, Boolean, () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(16f / 9f), titleLines = 3)
                KindBadge(book, accent, Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            LinearProgressIndicator(
                progress = { book.scrollProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                book.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menu) actions(book, true) { menu = false }
    }
}

/** One book on the shelf: its cover and how far in. Tap to open, hold for everything else. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookEntity,
    accent: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    actions: @Composable (BookEntity, Boolean, () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                KindBadge(book, accent, Modifier.align(Alignment.BottomStart).padding(3.dp))
            }
            LinearProgressIndicator(
                progress = { book.scrollProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        // Built only while open: it looks on disk for a backup of the text.
        if (menu) actions(book, true) { menu = false }
    }
}

/** Everything that can be done to a book besides opening it. Offers only what applies to this one. */
@Composable
private fun BookActionsMenu(
    book: BookEntity,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAuthor: () -> Unit,
    onDelete: () -> Unit,
    onReformat: () -> Unit,
    onReplace: () -> Unit,
    onPaste: () -> Unit,
    onRestore: () -> Unit,
    hasRawBackup: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenPdf: () -> Unit,
    onFindCover: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // The title first: a cover on its own does not always say which book it is.
        Text(
            book.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).width(220.dp),
        )
        HorizontalDivider()
        if (book.author.isNotBlank()) DropdownMenuItem(
            text = { Text("More by ${book.author}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = { onDismiss(); onAuthor() }
        )
        if (book.isReflowedText) {
            DropdownMenuItem(text = { Text("Tidy the text with your AI") }, onClick = { onDismiss(); onReformat() })
            DropdownMenuItem(text = { Text("Copy the text") }, onClick = { onDismiss(); onCopy() })
            DropdownMenuItem(text = { Text("Share the text") }, onClick = { onDismiss(); onShare() })
            DropdownMenuItem(text = { Text("Paste an edited version") }, onClick = { onDismiss(); onPaste() })
            DropdownMenuItem(text = { Text("Upload an edited version") }, onClick = { onDismiss(); onReplace() })
            if (hasRawBackup) {
                DropdownMenuItem(text = { Text("Restore the original text") }, onClick = { onDismiss(); onRestore() })
            }
        }
        if (book.format == "pdf") {
            DropdownMenuItem(text = { Text("Read the original PDF") }, onClick = { onDismiss(); onOpenPdf() })
        }
        if (!book.id.startsWith("paste-")) {
            DropdownMenuItem(
                text = { Text(if (book.coverUrl == null) "Find a cover" else "Look for a different cover") },
                onClick = { onDismiss(); onFindCover() }
            )
        }
        DropdownMenuItem(text = { Text("Delete book") }, onClick = { onDismiss(); onDelete() })
    }
}
