package com.pagetime.app.ui.screens.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Continuous-scroll PDF reader.
 *
 * Pages stack vertically like Samsung Notes: scroll through the document
 * naturally, pinch to zoom, drag to pan when zoomed in. No arrows, no
 * page-by-page switching — just the document as a continuous scroll.
 *
 * This is a display-only viewer for now. Highlights, flashcards, and
 * indexing can be added on top by storing page+rectangle coordinates,
 * but that is a separate piece of work from the reading experience.
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
    val activity = context as? Activity

    // --- Immersive mode ---
    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // --- Zoom / pan state ---
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDark by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showCreateFlashcard by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var isExtracting by remember { mutableStateOf(false) }

    val backgroundColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    val controlsColor = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f)

    // Auto-hide controls
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    // Load PDF
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

    // --- Pinch-to-zoom ---
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f; offsetY = 0f
        }
    }

    val scrollState = rememberLazyListState()

    val scope = rememberCoroutineScope()

    // Go to page: scroll the LazyColumn
    LaunchedEffect(showGoToPage) {
        if (!showGoToPage && state.currentPage >= 0) {
            // handled inside dialog
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        when {
            state.error != null -> {
                Text(
                    state.error!!,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.pageCount == 0 && state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                // --- Continuous scroll: pages stack vertically ---
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                        .transformable(state = transformState)
                        // Horizontal drag for panning when zoomed in
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                if (scale > 1f) {
                                    offsetX += dragAmount
                                }
                            }
                        }
                        // Tap to toggle controls, double-tap to toggle zoom
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { tapOffset ->
                                    if (scale > 1f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2f
                                        // Centre zoom on tap
                                        offsetX = size.width / 2f - tapOffset.x
                                        offsetY = size.height / 2f - tapOffset.y
                                    }
                                }
                            )
                        }
                ) {
                    items(state.pageCount) { pageIndex ->
                        PdfPageItem(
                            pageIndex = pageIndex,
                            isDark = isDark,
                            onPageVisible = { vm.markPage(it) },
                        )
                    }
                }
            }
        }

        // --- Top bar ---
        if (controlsVisible) {
            TopAppBar(
                title = {
                    if (state.pageCount > 0) {
                        Text("${state.currentPage + 1} / ${state.pageCount}")
                    } else {
                        Text("PDF")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(5f) }) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = {
                        scale = (scale / 1.5f).coerceAtLeast(1f)
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                    }) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { isDark = !isDark }) {
                        Icon(
                            if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = null
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Go to page…") },
                            onClick = { menuExpanded = false; showGoToPage = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Fit to screen") },
                            onClick = { menuExpanded = false; scale = 1f; offsetX = 0f; offsetY = 0f }
                        )
                        DropdownMenuItem(
                            text = { Text("Actual size") },
                            onClick = { menuExpanded = false; scale = 2.5f }
                        )
                        DropdownMenuItem(
                            text = { 
                                if (isExtracting) Text("Extracting…") 
                                else Text("Create flashcard from this page") 
                            },
                            onClick = {
                                menuExpanded = false
                                isExtracting = true
                                scope.launch {
                                    val text = vm.extractPageText(state.currentPage)
                                    extractedText = text
                                    isExtracting = false
                                    showCreateFlashcard = text != null
                                }
                            },
                            enabled = !isExtracting
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = controlsColor
                ),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    // --- Go-to-page dialog ---
    if (showGoToPage) {
        GoToPageDialog(
            pageCount = state.pageCount,
            onGoToPage = { page ->
                showGoToPage = false
                vm.goToPage(page)
            },
            onDismiss = { showGoToPage = false }
        )
    }

    // --- Flashcard creation dialog ---
    if (showCreateFlashcard && extractedText != null) {
        FlashcardFromPageDialog(
            pageText = extractedText!!,
            pageIndex = state.currentPage,
            onSave = { front, back ->
                vm.saveFlashcard(front, back, extractedText, state.currentPage)
                showCreateFlashcard = false
                extractedText = null
            },
            onDismiss = {
                showCreateFlashcard = false
                extractedText = null
            }
        )
    }
}

/**
 * Renders a single PDF page as a bitmap. Pages are rendered on demand
 * using PdfRenderer at 2× DPI for sharp text on high-density screens.
 */
@Composable
private fun PdfPageItem(
    pageIndex: Int,
    isDark: Boolean,
    onPageVisible: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(1.414f) } // A4 default

    LaunchedEffect(pageIndex) {
        onPageVisible(pageIndex)
        val app = context.applicationContext as com.pagetime.app.PageTimeApp
        val bmp = withContext(Dispatchers.Default) {
            PdfRendererHolder.renderPage(pageIndex)
        }
        if (bmp != null) {
            aspectRatio = bmp.height.toFloat() / bmp.width.toFloat()
            bitmap = bmp
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(if (isDark) Color(0xFF2A2A2A) else Color.White)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(if (isDark) Color(0xFF2A2A2A) else Color.White),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Shared PdfRenderer instance for the reading session.
 *
 * Keeps the renderer open across page changes so pages render quickly.
 * Closed when the ViewModel is cleared.
 */
object PdfRendererHolder {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0

    fun get(context: android.app.Application): PdfRenderer? = renderer

    fun open(context: android.app.Application, pdfPath: String): PdfRenderer? {
        close()
        val file = java.io.File(pdfPath)
        if (!file.exists()) return null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
            renderer
        } catch (e: Exception) {
            null
        }
    }

    fun renderPage(pageIndex: Int): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        val page = r.openPage(pageIndex)
        val scale = 2
        val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bmp
    }

    fun close() {
        renderer?.close()
        pfd?.close()
        renderer = null
        pfd = null
        pageCount = 0
    }
}

@Composable
private fun GoToPageDialog(
    pageCount: Int,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text("Page number") },
                supportingText = { Text("1 – $pageCount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val page = text.toIntOrNull()
                    if (page != null && page in 1..pageCount) {
                        onGoToPage(page - 1)
                    }
                },
                enabled = text.toIntOrNull()?.let { it in 1..pageCount } == true
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Dialog for creating a flashcard from extracted PDF page text.
 * Shows the full page text and lets the user write a question and answer.
 */
@Composable
private fun FlashcardFromPageDialog(
    pageText: String,
    pageIndex: Int,
    onSave: (front: String, back: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var showPageText by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create flashcard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Page ${pageIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text("Answer") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                TextButton(onClick = { showPageText = !showPageText }) {
                    Text(if (showPageText) "Hide page text" else "Show page text")
                }
                if (showPageText) {
                    Text(
                        pageText.take(2000) + if (pageText.length > 2000) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(front.trim(), back.trim()) },
                enabled = front.isNotBlank() && back.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
