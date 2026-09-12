package com.pagetime.app.ui.screens.reader

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * A native PDF reader that shows the document as it was printed.
 *
 * No text re-flow, no figure extraction, no conversion — just the pages
 * rendered by Android's PdfRenderer with swipe navigation and pinch-to-zoom.
 *
 * Gesture behaviour:
 * - At 1× zoom: swipe left/right changes pages, tap left/right third also works
 * - Zoomed in: drag to pan, pinch to adjust zoom
 * - Double-tap: toggle between 1× and ~2× zoom
 * - Single tap anywhere: toggle controls visibility
 *
 * The reader's timer runs while this screen is open, so reading time still
 * earns browse balance.
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
    val view = LocalView.current

    // --- Immersive mode: hide system bars for reading ---
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

    // --- State ---
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDark by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(-1f) } // -1 = system default
    var horizontalDragAccumulator by remember { mutableFloatStateOf(0f) }

    val backgroundColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    val controlsColor = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f)

    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Reset zoom when changing pages (clean slate per page)
    LaunchedEffect(state.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        horizontalDragAccumulator = 0f
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
                vm.open(pdfFile.absolutePath)
            } else {
                vm.open(book.localPath)
            }
        }
    }

    // --- Gesture state ---
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        if (newScale != scale) {
            // Zoom centred on the gesture centroid — handled via graphicsLayer
            scale = newScale
        }
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Scaffold(
        topBar = {
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
                        // Zoom buttons
                        IconButton(onClick = {
                            scale = (scale * 1.5f).coerceAtMost(5f)
                        }) {
                            Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom in")
                        }
                        IconButton(onClick = {
                            scale = (scale / 1.5f).coerceAtLeast(1f)
                            if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                        }) {
                            Icon(Icons.Outlined.ZoomOut, contentDescription = "Zoom out")
                        }
                        // Theme toggle
                        IconButton(onClick = { isDark = !isDark }) {
                            Icon(
                                if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                                contentDescription = if (isDark) "Light mode" else "Dark mode"
                            )
                        }
                        // Overflow menu
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Go to page…") },
                                onClick = { menuExpanded = false; showGoToPage = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Fit to screen") },
                                onClick = {
                                    menuExpanded = false
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Actual size (100%)") },
                                onClick = {
                                    menuExpanded = false
                                    // 100% means 1 PDF point = 1 pixel at 72 DPI
                                    // On a typical phone (~400 DPI), that's very small
                                    // Use a reasonable "actual size" that's readable
                                    scale = 2.5f
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Brightness…") },
                                onClick = { menuExpanded = false; showBrightness = true }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = controlsColor
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(padding)
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
                    Image(
                        bitmap = state.bitmap!!.asImageBitmap(),
                        contentDescription = "PDF page ${state.currentPage + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                            // Pinch-to-zoom and pan
                            .transformable(state = transformState)
                            // Horizontal drag: pan when zoomed, page swipe when at 1×
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart = { horizontalDragAccumulator = 0f },
                                    onDragEnd = {
                                        if (scale <= 1.05f) {
                                            when {
                                                horizontalDragAccumulator < -80 -> vm.nextPage()
                                                horizontalDragAccumulator > 80 -> vm.prevPage()
                                            }
                                        }
                                        horizontalDragAccumulator = 0f
                                    },
                                    onDragCancel = { horizontalDragAccumulator = 0f }
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (scale > 1.05f) {
                                        // Pan when zoomed in
                                        offsetX += dragAmount
                                    } else {
                                        // At 1×: accumulate for page-swipe detection
                                        horizontalDragAccumulator += dragAmount
                                    }
                                }
                            }
                            // Tap and double-tap
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        controlsVisible = !controlsVisible
                                    },
                                    onDoubleTap = { tapOffset ->
                                        if (scale > 1f) {
                                            // Zoom out to 1×
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            // Zoom in to 2× centred on tap
                                            scale = 2f
                                            offsetX = size.width / 2f - tapOffset.x
                                            offsetY = size.height / 2f - tapOffset.y
                                        }
                                    }
                                )
                            }
                    )
                }
                state.loading -> {
                    Text(
                        "Loading…",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- Page controls (bottom bar) ---
            if (controlsVisible && state.pageCount > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(controlsColor)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { vm.prevPage() },
                        enabled = state.currentPage > 0
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "Previous page",
                            tint = if (state.currentPage > 0) Color.White else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Page scrubber
                    Slider(
                        value = state.currentPage.toFloat(),
                        onValueChange = { },
                        onValueChangeFinished = { },
                        valueRange = 0f..(state.pageCount - 1).coerceAtLeast(1).toFloat(),
                        steps = (state.pageCount - 2).coerceAtLeast(0),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    IconButton(
                        onClick = { vm.nextPage() },
                        enabled = state.currentPage < state.pageCount - 1
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "Next page",
                            tint = if (state.currentPage < state.pageCount - 1) Color.White else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }

    // --- Go-to-page dialog ---
    if (showGoToPage) {
        GoToPageDialog(
            currentPage = state.currentPage,
            pageCount = state.pageCount,
            onGoToPage = { page -> vm.goToPage(page); showGoToPage = false },
            onDismiss = { showGoToPage = false }
        )
    }

    // --- Brightness dialog ---
    if (showBrightness) {
        BrightnessDialog(
            brightness = brightness,
            onBrightnessChange = { brightness = it },
            onDismiss = { showBrightness = false }
        )
    }
}

@Composable
private fun GoToPageDialog(
    currentPage: Int,
    pageCount: Int,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }; error = null },
                label = { Text("Page number") },
                supportingText = { Text("1 – $pageCount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = error != null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val page = text.toIntOrNull()
                    if (page != null && page in 1..pageCount) {
                        onGoToPage(page - 1)
                    } else {
                        error = "Enter a number between 1 and $pageCount"
                    }
                }
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BrightnessDialog(
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Brightness") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = -1f..1f,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Outlined.LightMode, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (brightness <= -0.99f) "System default" else "${((brightness + 1f) / 2f * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
