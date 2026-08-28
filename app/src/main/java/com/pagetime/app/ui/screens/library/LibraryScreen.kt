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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.pagetime.app.data.youtube.YouTubeTranscriptFetcher
import com.pagetime.app.ui.AppPrimaryButton
import com.pagetime.app.ui.Spacing
import com.pagetime.app.ui.formatMinutes

private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
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
    viewModel: LibraryViewModel = viewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val balanceSeconds by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsStateWithLifecycle()
    val lastMapMoment by viewModel.lastMapMoment.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val reformatProgress by viewModel.reformatProgress.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var replaceBook by remember { mutableStateOf<BookEntity?>(null) }
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PageTime", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Your library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Timer,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = formatMinutes(balanceSeconds),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
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
                    "Download from Discover — or import an EPUB or plain-text file from your phone.",
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
                AppPrimaryButton(
                    text = "Discover free books",
                    onClick = onDiscover
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = Spacing.m,
                    end = Spacing.m,
                    top = Spacing.m,
                    bottom = Spacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                item {
                    Text(
                        "${formatMinutes(totalReadingSeconds)} read · ${formatMinutes(balanceSeconds)} earned",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                item {
                    AppPrimaryButton(
                        text = if (importing) "Importing…" else "Import EPUB or text",
                        icon = Icons.Filled.Add,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = launchImport
                    )
                }
                item {
                    AppPrimaryButton(
                        text = if (importing) "Importing…" else "Import YouTube transcript",
                        icon = Icons.Filled.Add,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = launchYouTubeImport
                    )
                }
                if (lastMapMoment != null) {
                    item {
                        books.firstOrNull { it.id == lastMapMoment?.bookId }?.let { book ->
                            ContinueThinkingCard(
                                book = book,
                                moment = lastMapMoment!!,
                                onClick = { onOpenConcepts(book.id) }
                            )
                        }
                    }
                }
                items(books, key = { it.id }) { book ->
                    val isReformatting by viewModel.reformatting.collectAsStateWithLifecycle()
                    BookRow(
                        book = book,
                        onClick = { onOpenBook(book.id) },
                        onDelete = { viewModel.delete(book) },
                        onReformat = { viewModel.reformatWithAI(book.id) },
                        onReplace = { replaceBook = book; replacePicker.launch(arrayOf("text/plain", "text/*")) },
                        onRestore = { viewModel.restoreTranscript(book.id) },
                        hasRawBackup = viewModel.hasRawBackup(book),
                        isReformatting = book.id in isReformatting,
                        reformatProgress = reformatProgress[book.id]
                    )
                }
            }
        }
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
}

@Composable
private fun ContinueThinkingCard(book: BookEntity, moment: MapMoment, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Continue your thinking", style = MaterialTheme.typography.titleMedium)
            Text(book.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${moment.conceptCount} new ideas · ${moment.relationshipCount} connections",
                style = MaterialTheme.typography.bodyMedium
            )
            moment.featuredConcept?.let { concept ->
                Text(
                    moment.featuredRelationship?.let { "$concept $it" } ?: concept,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Explore the map in about 2 minutes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onReplace: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    hasRawBackup: Boolean = false,
    onReformat: (() -> Unit)? = null,
    isReformatting: Boolean = false,
    reformatProgress: Pair<Int, Int>? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (book.coverUrl != null) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatMinutes(book.totalReadingSeconds)} read · ${book.format.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (book.scrollProgress > 0f) {
                        Spacer(Modifier.width(10.dp))
                        LinearProgressIndicator(
                            progress = { book.scrollProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(book.scrollProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (onReformat != null && book.format == "txt") {
                    IconButton(
                        onClick = onReformat,
                        enabled = !isReformatting
                    ) {
                        if (isReformatting) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                reformatProgress?.let { (completed, total) ->
                                    Text(
                                        "$completed/$total",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = "AI Format",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (onReplace != null && book.format == "txt") {
                    IconButton(onClick = onReplace) {
                        Text("↺", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (onRestore != null && hasRawBackup) {
                    IconButton(onClick = onRestore) {
                        Text("⟲", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (onReplace != null && book.format == "txt") {
                    IconButton(onClick = onReplace) { Text("↺", style = MaterialTheme.typography.titleLarge) }
                }
                if (onRestore != null && hasRawBackup) {
                    IconButton(onClick = onRestore) { Text("⟲", style = MaterialTheme.typography.titleLarge) }
                }
                if (onReplace != null && book.format == "txt") {
                    IconButton(onClick = onReplace) { Text("↺", style = MaterialTheme.typography.titleLarge) }
                }
                if (onRestore != null && hasRawBackup) {
                    IconButton(onClick = onRestore) { Text("⟲", style = MaterialTheme.typography.titleLarge) }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
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