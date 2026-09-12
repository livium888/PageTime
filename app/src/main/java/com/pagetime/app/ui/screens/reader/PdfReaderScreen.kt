package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.delay

/**
 * A native PDF reader that shows the document as it was printed.
 *
 * No text re-flow, no figure extraction, no conversion — just the pages
 * rendered by Android's PdfRenderer with swipe navigation and pinch-to-zoom.
 * The reader's timer runs while this screen is open, so reading time still
 * earns browse balance.
 *
 * This is the Samsung Notes experience: open the PDF, pinch to zoom, swipe
 * between pages, and read the document as the author laid it out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    vm: PdfReaderViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Find the PDF file for this book
    LaunchedEffect(bookId) {
        val app = context.applicationContext as com.pagetime.app.PageTimeApp
        val bookDao = app.container.database.bookDao()
        val book = bookDao.getById(bookId)
        if (book != null && book.format == "pdf") {
            val repo = app.container.libraryRepository
            val pdfFile = repo.pdfSourceFile(book)
            if (pdfFile != null && pdfFile.exists()) {
                vm.open(pdfFile.absolutePath)
            } else {
                vm.open(book.localPath) // fallback
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.pageCount > 0) "Page ${state.currentPage + 1} of ${state.pageCount}"
                        else "PDF Reader"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5)) // light grey like paper
        ) {
            when {
                state.error != null -> {
                    Text(
                        state.error!!,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.bitmap != null -> {
                    PdfPage(
                        bitmap = state.bitmap!!,
                        onPageTap = { tapX ->
                            // Tap left third = previous, right third = next
                            if (tapX < 0.33f) vm.prevPage()
                            else if (tapX > 0.67f) vm.nextPage()
                        },
                        onSwipeLeft = { vm.nextPage() },
                        onSwipeRight = { vm.prevPage() },
                    )
                }
                else -> {
                    Text(
                        "Loading…",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Bottom page controls
            if (state.pageCount > 1) {
                PdfPageControls(
                    currentPage = state.currentPage,
                    pageCount = state.pageCount,
                    onPrev = { vm.prevPage() },
                    onNext = { vm.nextPage() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * A single PDF page with pinch-to-zoom and swipe navigation.
 */
@Composable
private fun PdfPage(
    bitmap: android.graphics.Bitmap,
    onPageTap: (Float) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // If zoomed in, taps should pan, not navigate
                    if (scale <= 1f) {
                        onPageTap(offset.x / size.width)
                    }
                }
            }
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "PDF page",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }


}

@Composable
private fun PdfPageControls(
    currentPage: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrev,
            enabled = currentPage > 0,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous page",
                tint = if (currentPage > 0) Color.White else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            "${currentPage + 1} / $pageCount",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(
            onClick = onNext,
            enabled = currentPage < pageCount - 1,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next page",
                tint = if (currentPage < pageCount - 1) Color.White else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
