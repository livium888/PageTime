package com.pagetime.app.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pagetime.app.data.catalog.CatalogHealth
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.youtube.YouTubeSearchApi
import androidx.compose.foundation.layout.height

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = viewModel()) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val youtubeResults by viewModel.youtubeResults.collectAsStateWithLifecycle()
    val importingVideo by viewModel.importingVideo.collectAsStateWithLifecycle()
    val categoryShelves by viewModel.categoryShelves.collectAsStateWithLifecycle()
    val searchingAll by viewModel.searchingAll.collectAsStateWithLifecycle()

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // Show download errors as a snackbar so the user knows why a download failed.
    LaunchedEffect(error) {
        if (error != null && books.isNotEmpty()) {
            snackbarHostState.showSnackbar(error!!)
            viewModel.clearError()
        }
    }

    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 6
        }
    }
    // Scroll back to the top whenever the source changes.
    LaunchedEffect(source) { listState.scrollToItem(0) }
    LaunchedEffect(shouldLoadMore, source) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Discover") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(source.searchHint) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                )
                OutlinedButton(
                    onClick = {
                        if (source is DiscoverSource.Videos) viewModel.searchYouTube()
                        else viewModel.searchAllSources()
                    },
                    enabled = query.isNotBlank() && !loading && !searchingAll,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (searchingAll) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Search")
                    }
                }
            }
            // Source selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                viewModel.sources.forEach { s ->
                    FilterChip(
                        selected = source.id == s.id,
                        onClick = { viewModel.onSourceChange(s) },
                        label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                books.isEmpty() && youtubeResults.isEmpty() && categoryShelves.isEmpty() &&
                    health != CatalogHealth.Working -> EmptyShelf(
                    health = health,
                    onRetry = {
                        if (source is DiscoverSource.Videos) viewModel.searchYouTube()
                        else viewModel.retry()
                    }
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (source is DiscoverSource.Videos) {
                        if (categoryShelves.isNotEmpty() && youtubeResults.isEmpty()) {
                            // Show browse categories when no search query
                            items(categoryShelves, key = { it.title }) { shelf ->
                                CategoryShelfRow(
                                    shelf = shelf,
                                    onImport = { videoId ->
                                        viewModel.importYouTubeVideo(videoId)
                                    }
                                )
                            }
                        } else {
                            items(youtubeResults, key = { it.videoId }) { video ->
                                YouTubeVideoRow(
                                    video = video,
                                    isImporting = video.videoId in importingVideo,
                                    onImport = { viewModel.importYouTubeVideo(video.videoId) }
                                )
                            }
                        }
                    } else {
                        items(books, key = { it.id }) { book ->
                            BookRow(
                                book = book,
                                downloaded = book.id.toString() in downloadedIds,
                                downloading = book.id.toString() in downloading,
                                onDownload = { viewModel.download(book) }
                            )
                        }
                    }
                    if (loadingMore) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    if (!hasMore && books.isNotEmpty()) {
                        item {
                            Text(
                                "You've reached the end of the catalog",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: GutendexBook,
    downloaded: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
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
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
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
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    book.authorName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (book.source) {
                        "standardebooks" -> "Source · Standard Ebooks"
                        "openlibrary" -> "Source · Open Library"
                        "internetarchive" -> "Source · Internet Archive"
                        else -> "Source · Project Gutenberg · ${book.downloadCount} downloads"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                downloading -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                downloaded -> Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
                else -> Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Get", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun YouTubeVideoRow(
    video: YouTubeSearchApi.SearchResult,
    isImporting: Boolean,
    onImport: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasDescription = video.description.isNotBlank()

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .height(90.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        video.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        video.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (video.viewCount.isNotBlank()) {
                            Text(
                                video.viewCount,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (video.duration.isNotBlank()) {
                            Text(
                                video.duration,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onImport,
                    enabled = !isImporting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isImporting) "Importing…" else "Read", style = MaterialTheme.typography.labelLarge)
                }
            }
            // Expandable description preview
            if (hasDescription) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = video.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { mod ->
                            if (!expanded) mod.then(
                                Modifier.clickable { expanded = true }
                            ) else mod
                        }
                )
                if (expanded) {
                    Text(
                        "Show less",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { expanded = false }
                    )
                } else if (video.description.length > 100) {
                    Text(
                        "Show more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { expanded = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryShelfRow(
    shelf: YouTubeSearchApi.CategoryShelf,
    onImport: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            shelf.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shelf.results.size) { index ->
                val video = shelf.results[index]
                CategoryVideoCard(
                    video = video,
                    onImport = { onImport(video.videoId) }
                )
            }
        }
    }
}

@Composable
private fun CategoryVideoCard(
    video: YouTubeSearchApi.SearchResult,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable { onImport() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (video.viewCount.isNotBlank()) {
                    Text(
                        video.viewCount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * What the shelf shows when it holds no books.
 *
 * The point of this composable is that those are different situations. A
 * catalogue that answered and had nothing, a catalogue that needs a query
 * first, and a catalogue that did not answer at all were previously one blank
 * list with one message — so a source whose feed had quietly died was
 * indistinguishable from a search that genuinely found nothing, and the reader
 * had no way to tell whether to change their words or their source.
 */
@Composable
private fun EmptyShelf(health: CatalogHealth, onRetry: () -> Unit) {
    val title: String
    val detail: String
    val isFault: Boolean
    when (health) {
        is CatalogHealth.Unreachable -> {
            title = "${health.label} didn\u2019t answer"
            detail = health.detail
            isFault = true
        }

        is CatalogHealth.NothingMatched -> {
            title =
                if (health.query.isBlank()) "${health.label} returned nothing"
                else "Nothing in ${health.label} matched \u201C${health.query}\u201D"
            detail = health.note
            isFault = false
        }

        is CatalogHealth.NoneDownloadable -> {
            title =
                if (health.found == 1) "${health.label} found 1 book it can\u2019t hand over"
                else "${health.label} found ${health.found} books it can\u2019t hand over"
            detail = "They are page scans or library loans, with no file to download. " +
                "Try different words, or another source."
            isFault = false
        }

        is CatalogHealth.NeedsQuery -> {
            title = "${health.label} needs something to look for"
            detail = health.note
            isFault = false
        }

        is CatalogHealth.PartlyReachable -> {
            title = "Some catalogues didn\u2019t answer"
            detail = health.silent.joinToString(", ")
            isFault = true
        }

        CatalogHealth.Working -> return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isFault) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            // Only a fault is worth retrying. Offering "Retry" on a search that
            // simply matched nothing invites the reader to run it again and get
            // the same nothing.
            if (isFault) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}
