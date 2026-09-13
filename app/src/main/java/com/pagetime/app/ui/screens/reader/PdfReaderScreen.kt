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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    // Measured page shapes, so a page reclaimed by the lazy list comes back at
    // its true height instead of settling into it.
    val pageRatios by vm.pageRatios.collectAsStateWithLifecycle()
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
    // Saveable rather than merely remembered: turning the phone builds a new
    // Activity, and plain `remember` came back at 1x with dark mode off. The
    // page appeared to move because the view around it had been rebuilt, not
    // because the reader asked it to.
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    var isDark by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showTextSheet by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf<String?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var selectedTextInSheet by remember { mutableStateOf("") }

    // --- Where the reader is, for when the phone turns ---
    // The width is what a rotation changes, and with it the height of every
    // page: a page is drawn full-bleed, so a wider screen means a taller page.
    // The anchor is captured during composition, before the new layout can
    // re-report the old pixel offset as though it still meant something; the
    // scroll observer further down only starts after this has been read.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    val markerHeightPx = with(density) { PageMarkerHeight.toPx() }
    val anchorOnEntry = remember(screenWidthDp) { vm.currentReadingAnchor() }
    var listWidthPx by remember { mutableIntStateOf(0) }

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
            // Consumed, not merely scrolled to. Leaving it set is what made
            // rotation jump back to the page the book was opened at: the
            // effect re-ran on every recreation and won against the position
            // the list had saved for itself.
            vm.clearRestoredPage()
        }
    }
    LaunchedEffect(state.targetScrollPage) {
        state.targetScrollPage?.let { page ->
            delay(100)
            scrollState.animateScrollToItem(page)
            vm.clearTargetScrollPage()
        }
    }

    // --- Remember the place as a fraction, not a number of pixels ---
    // Android's own restore of a list's scroll offset is in pixels, which a
    // rotation invalidates; this is the value that is kept instead.
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val index = scrollState.firstVisibleItemIndex
            val item = scrollState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            Triple(index, scrollState.firstVisibleItemScrollOffset, item?.size ?: 0)
        }.collect { (index, offset, size) ->
            vm.recordReadingAnchor(index, ReaderPositionPolicy.fractionOf(offset, size))
        }
    }

    // --- Put the reader back on the line they were on ---
    // Runs on the new layout after a rotation (and after any other
    // recreation), where the list has just restored a pixel offset that no
    // longer points at the same place. The fraction does, so the offset is
    // recomputed from the page's height at the new width.
    LaunchedEffect(screenWidthDp, state.pageCount) {
        val anchor = anchorOnEntry ?: return@LaunchedEffect
        if (state.pageCount == 0) return@LaunchedEffect
        val width = if (listWidthPx > 0) {
            listWidthPx
        } else {
            snapshotFlow { listWidthPx }.first { it > 0 }
        }
        val itemHeight = markerHeightPx + width / (pageRatios[anchor.page] ?: A4_PAGE_RATIO)
        scrollState.scrollToItem(
            anchor.page,
            ReaderPositionPolicy.offsetFor(anchor.fraction, itemHeight),
        )
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
                        .onSizeChanged { listWidthPx = it.width }
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
                        Column(Modifier.fillMaxWidth()) {
                            PageStartMarker(pageIndex = pageIndex, isDark = isDark)
                            PdfPageItem(
                                pageIndex = pageIndex,
                                isDark = isDark,
                                knownRatio = pageRatios[pageIndex],
                                onPageVisible = { vm.markPage(it) },
                                onPageSized = { index, ratio ->
                                    vm.recordPageRatio(index, ratio)
                                },
                            )
                        }
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
 * The filter that makes a rendered PDF page dark.
 *
 * A page arrives as one bitmap. The renderer draws white paper and black ink
 * and has no notion of a theme, so there is no text node to recolour and no
 * vector to re-draw — the pixels are the only lever. The handle used here is
 * the one every reader ends up with, "smart invert": invert the lightness,
 * then rotate the hue 180° so it comes back. Black ink on white paper becomes
 * white on black, and a colour diagram keeps roughly its own hue instead of
 * turning cyan.
 *
 * The two steps are composed by hand into a single 4x5 matrix, which is
 * possible because the hue rotation's rows each sum to one: rotating after an
 * inversion is the same as negating the rotation and adding 255. Doing it at
 * DRAW time rather than at render time means the toggle is instant and the
 * cached page bitmaps never have to be re-rendered.
 *
 * WHAT THIS CANNOT DO. A page is a single bitmap, so the text and the figures
 * on it cannot be separated: a photograph or a colour plate is inverted along
 * with everything else and comes back as a negative. There is no "dark text,
 * untouched images" setting to offer here without re-rendering the document,
 * which PdfRenderer does not do.
 */
private val PdfDarkPageFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/** A4, as the placeholder shape for a page whose bitmap has not arrived yet. */
private const val A4_PAGE_RATIO = 0.7071f // 210 / 297, width over height

/**
 * The strip that marks where a page begins.
 *
 * Pages are drawn edge to edge, so without this there is nothing between the
 * end of one and the start of the next — in dark mode not even a colour
 * change, just one continuous black column. The label names the page that
 * starts below it, which is what makes a page boundary legible while a page
 * taller than the screen is still being scrolled through.
 *
 * Fixed height on purpose: the reading position is remembered as a fraction of
 * the whole list item, so this strip has to measure the same before and after a
 * rotation for the page under it to come back in the same place.
 */
private val PageMarkerHeight = 30.dp

@Composable
private fun PageStartMarker(pageIndex: Int, isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PageMarkerHeight)
            .padding(start = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Page ${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.36f),
        )
    }
}

/**
 * Renders a single PDF page as a bitmap.
 *
 * [knownRatio] is this session's measured shape for the page — its width
 * divided by its height, which is the direction `Modifier.aspectRatio` takes —
 * when it has been seen before. Seeding from it is what lets a rotated view
 * come back to the same place: a placeholder that claims the wrong height
 * moves everything below it, and the list keeps its scroll offset in pixels.
 */
@Composable
private fun PdfPageItem(
    pageIndex: Int,
    isDark: Boolean,
    knownRatio: Float?,
    onPageVisible: (Int) -> Unit = {},
    onPageSized: (Int, Float) -> Unit = { _, _ -> },
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageRatio by remember(pageIndex) {
        mutableFloatStateOf(knownRatio ?: A4_PAGE_RATIO)
    }

    LaunchedEffect(pageIndex) {
        onPageVisible(pageIndex)
        val bmp = withContext(Dispatchers.Default) {
            PdfRendererHolder.renderPage(pageIndex)
        }
        if (bmp != null) {
            // Width over height, which is what `aspectRatio` expects. Passed
            // the other way round it sized the slot at half the height the
            // page needed, so a page's bitmap overflowed and was covered by
            // the one after it — the reason the start of a page could not be
            // seen, and the reason the markers above needed a layout that
            // matches the artwork before they could show it.
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            pageRatio = ratio
            bitmap = bmp
            onPageSized(pageIndex, ratio)
        }
    }

    // The slot is the paper: the colour of the page it is waiting for, so
    // nothing flashes white on the way in.
    val pageColor = if (isDark) Color.Black else Color.White
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            contentScale = ContentScale.FillWidth,
            colorFilter = if (isDark) PdfDarkPageFilter else null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(pageRatio)
                .background(pageColor)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(pageRatio)
                .background(pageColor),
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
