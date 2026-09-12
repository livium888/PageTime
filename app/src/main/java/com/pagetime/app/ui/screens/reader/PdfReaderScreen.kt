package com.pagetime.app.ui.screens.reader

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/**
 * A native PDF reader — Samsung Notes style.
 *
 * - Full-screen immersive: no bars by default, the PDF fills every pixel
 * - Tap to show/hide chrome (top bar + page indicator)
 * - Pinch to zoom, swipe or tap to navigate pages
 * - Position saved and restored across sessions
 * - Timer runs while reading (wired by the caller)
 *
 * The design principle: the PDF IS the interface. Chrome appears only
 * when asked for and disappears on its own.
 */
@Composable
fun PdfReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    vm: PdfReaderViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Immersive mode: hide system bars
    val activity = context as? android.app.Activity
    LaunchedEffect(activity) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Restore system bars when leaving
    LaunchedEffect(Unit) {
        // no cleanup needed — onBack handles navigation which restores bars
    }

    // Find the PDF file for this book
    LaunchedEffect(bookId) {
        val app = context.applicationContext as com.pagetime.app.PageTimeApp
        val bookDao = app.container.database.bookDao()
        val book = bookDao.getById(bookId)
        if (book != null && book.format == "pdf") {
            val repo = app.container.libraryRepository
            val pdfFile = repo.pdfSourceFile(book)
            if (pdfFile != null && pdfFile.exists()) {
                vm.open(pdfFile.absolutePath, bookId)
            } else {
                vm.open(book.localPath, bookId)
            }
        }
    }

    // Chrome visibility — hidden by default, shown on tap, auto-hides
    var chromeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(3000)
            chromeVisible = false
        }
    }

    // The entire screen is the PDF. Tap toggles chrome.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5.toInt()))
            .pointerInput(Unit) {
                detectTapGestures { _ ->
                    chromeVisible = !chromeVisible
                }
            }
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
                    onPageTapLeft = { vm.prevPage() },
                    onPageTapRight = { vm.nextPage() },
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

        // Top bar — only visible when chrome is toggled
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    "${state.currentPage + 1} / ${state.pageCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                // Spacer to balance the back button
                Spacer(Modifier.width(48.dp))
            }
        }

        // Bottom page nav — only visible when chrome is toggled
        AnimatedVisibility(
            visible = chromeVisible && state.pageCount > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { vm.prevPage() },
                    enabled = state.currentPage > 0,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = "Previous",
                        tint = if (state.currentPage > 0) Color.White else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { vm.nextPage() },
                    enabled = state.currentPage < state.pageCount - 1,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = "Next",
                        tint = if (state.currentPage < state.pageCount - 1) Color.White else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single PDF page with pinch-to-zoom and tap navigation.
 *
 * Tap left third = previous page, right third = next page.
 * Pinch to zoom — when zoomed, pan instead of navigating.
 */
@Composable
private fun PdfPage(
    bitmap: android.graphics.Bitmap,
    onPageTapLeft: () -> Unit,
    onPageTapRight: () -> Unit,
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
                    if (scale <= 1f) {
                        val x = offset.x / size.width
                        if (x < 0.4f) onPageTapLeft()
                        else if (x > 0.6f) onPageTapRight()
                    } else {
                        // Zoomed in: double-tap resets zoom
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
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
