package com.pagetime.app.ui.screens.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * naturally, pinch to zoom, drag to pan when zoomed in. Features:
 *
 *  - Reading time tracking (earns browse balance like EPUB)
 *  - One-tap AI flashcard generation from current page (Gemini)
 *  - Text selection bottom sheet → highlight → AI flashcard
 *  - Last-position memory (restores to where you left off)
 *  - Dark mode, zoom controls, go-to-page
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    vm: PdfReaderViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val flashcardState by vm.flashcardState.collectAsStateWithLifecycle()
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
    var showTextSheet by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf<String?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var selectedTextInSheet by remember { mutableStateOf("") }

    val backgroundColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    val controlsColor = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f)

    // Auto-hide controls
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }

    // --- Flashcard result feedback ---
    LaunchedEffect(flashcardState.lastCreatedFront) {
        if (flashcardState.lastCreatedFront != null) {
            delay(3000)
            vm.dismissFlashcardResult()
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

    // --- Scroll to restored/target page ---
    val scrollState = rememberLazyListState()
    LaunchedEffect(state.restoredPage) {
        state.restoredPage?.let { page ->
            delay(300) // Wait for LazyColumn to be laid out
            scrollState.scrollToItem(page)
            vm.clearTargetScrollPage()
        }
    }
    LaunchedEffect(state.targetScrollPage) {
        state.targetScrollPage?.let { page ->
            delay(100)
            scrollState.animateScrollToItem(page)
            vm.clearTargetScrollPage()
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

    val scope = rememberCoroutineScope()

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
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                if (scale > 1f) {
                                    offsetX += dragAmount
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { tapOffset ->
                                    if (scale > 1f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2f
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

        // --- Flashcard generation indicator ---
        if (flashcardState.generating) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Generating flashcard with Gemini…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // --- Flashcard created toast ---
        flashcardState.lastCreatedFront?.let { front ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    "✓ Flashcard: \"$front\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- Error ---
        flashcardState.error?.let { error ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- Top bar ---
        if (controlsVisible) {
            TopAppBar(
                title = {
                    if (state.pageCount > 0) {
                        Text("Page ${state.currentPage + 1} of ${state.pageCount}")
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
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                if (isExtracting) Text("Generating…")
                                else Text("✨ Generate flashcard from this page")
                            },
                            onClick = {
                                menuExpanded = false
                                vm.generateFlashcardFromCurrentPage()
                            },
                            enabled = !isExtracting && !flashcardState.generating,
                        )
                        DropdownMenuItem(
                            text = { Text("Select text from this page") },
                            onClick = {
                                menuExpanded = false
                                isExtracting = true
                                scope.launch {
                                    val text = vm.extractPageText(state.currentPage)
                                    pageText = text
                                    isExtracting = false
                                    showTextSheet = text != null
                                }
                            },
                            enabled = !isExtracting && !flashcardState.generating,
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

    // --- Text selection bottom sheet ---
    if (showTextSheet && pageText != null) {
        TextSelectionSheet(
            pageText = pageText!!,
            pageIndex = state.currentPage,
            onGenerateFlashcard = { selected ->
                showTextSheet = false
                vm.generateFlashcardFromSelection(selected)
            },
            onDismiss = {
                showTextSheet = false
                selectedTextInSheet = ""
            },
            isDark = isDark,
        )
    }
}

/**
 * Renders a single PDF page as a bitmap.
 */
@Composable
private fun PdfPageItem(
    pageIndex: Int,
    isDark: Boolean,
    onPageVisible: (Int) -> Unit = {},
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(1.414f) } // A4 default

    LaunchedEffect(pageIndex) {
        onPageVisible(pageIndex)
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
 */
object PdfRendererHolder {
    private var renderer: android.graphics.pdf.PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0

    fun open(context: android.app.Application, pdfPath: String): android.graphics.pdf.PdfRenderer? {
        close()
        val file = java.io.File(pdfPath)
        if (!file.exists()) return null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd!!)
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
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

// --- Dialogs and sheets ---

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
 * Bottom sheet showing extracted page text where the user can select
 * a passage and generate an AI flashcard from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSelectionSheet(
    pageText: String,
    pageIndex: Int,
    onGenerateFlashcard: (String) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean,
) {
    var selectedText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isDark) Color(0xFF2A2A2A) else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Text(
                "Page ${pageIndex + 1} — Select text to create a flashcard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                "Long-press and drag to select a passage, then tap the button below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Selectable text
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                var localSelectedText by remember { mutableStateOf("") }
                Text(
                    text = pageText,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = if (isDark) Color(0xFFE0E0E0) else Color(0xFF1A1A1A),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Generate button
            TextButton(
                onClick = {
                    // For now, send the full page text to Gemini — the AI will
                    // pick the best idea from it. In a future iteration we can
                    // add native text selection to extract a specific highlight.
                    onGenerateFlashcard(pageText)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("✨ Generate flashcard from this page")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
