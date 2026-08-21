package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.library.EpubChapter
import com.pagetime.app.ui.formatClock
import com.pagetime.app.ui.formatMinutes
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModelFactory(app, bookId))

    val book by vm.book.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val chapterIndex by vm.chapterIndex.collectAsStateWithLifecycle()
    val extractRoot by vm.extractRoot.collectAsStateWithLifecycle()
    val textContent by vm.textContent.collectAsStateWithLifecycle()
    val sessionSeconds by vm.sessionSeconds.collectAsStateWithLifecycle()
    val balanceSeconds by vm.balanceSeconds.collectAsStateWithLifecycle()
    val readerError by vm.error.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // Run the reading timer while the reader is in the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.startReading()
                Lifecycle.Event.ON_PAUSE -> vm.stopReading()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopReading()
            if (book?.format == "txt" && scrollState.maxValue > 0) {
                vm.updateScrollProgress(scrollState.value.toFloat() / scrollState.maxValue)
            }
        }
    }

    // Restore plain-text scroll position once the text has been laid out.
    LaunchedEffect(textContent) {
        if (textContent != null && book?.format == "txt") {
            delay(80)
            val target = (book?.scrollProgress ?: 0f) * scrollState.maxValue
            if (target > 0) scrollState.scrollTo(target.toInt())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.title ?: "Reading",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            formatMinutes(balanceSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Reading this session: ${formatClock(sessionSeconds)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                book == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No book to read yet.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Download a book from Discover first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                book?.format == "epub" && chapters.isNotEmpty() && extractRoot != null -> EpubReader(
                    chapters = chapters,
                    chapterIndex = chapterIndex,
                    extractRoot = extractRoot!!,
                    onPrev = { vm.goToChapter(chapterIndex - 1) },
                    onNext = { vm.goToChapter(chapterIndex + 1) }
                )

                book?.format == "txt" && textContent != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        textContent!!,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Serif
                    )
                }

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (readerError != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                readerError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { vm.retry() }) { Text("Retry") }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubReader(
    chapters: List<EpubChapter>,
    chapterIndex: Int,
    extractRoot: String,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val chapter = chapters[chapterIndex]
    val file = remember(chapter, extractRoot) { File(extractRoot, chapter.filePath) }
    val url = "file://${file.absolutePath}"

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = chapterIndex > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous chapter")
            }
            Text(
                chapter.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${chapterIndex + 1} / ${chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onNext, enabled = chapterIndex < chapters.size - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next chapter")
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            },
            update = { webView ->
                if (webView.url != url) webView.loadUrl(url)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}
