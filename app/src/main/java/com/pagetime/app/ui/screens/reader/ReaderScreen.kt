package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.library.EpubChapter
import com.pagetime.app.data.local.ReaderSettings
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
    val creditedSeconds by vm.creditedSeconds.collectAsStateWithLifecycle()
    val balanceSeconds by vm.balanceSeconds.collectAsStateWithLifecycle()
    val readerError by vm.error.collectAsStateWithLifecycle()
    val guardState by vm.guardState.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val settings by vm.readerSettings.collectAsStateWithLifecycle()

    val palette = paletteFor(settings.theme)
    val scrollState = rememberScrollState()

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

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

    // Feed the anti-cheat guard with plain-text scroll movement + progress.
    LaunchedEffect(textContent) {
        if (textContent == null) return@LaunchedEffect
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (value, max) ->
                vm.onUserScrolled()
                val fraction = if (max > 0) value.toFloat() / max else 0f
                vm.onProgressChanged(fraction)
            }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = palette.background,
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
                        if (chapters.isNotEmpty()) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(Icons.Filled.List, contentDescription = "Chapters")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.FormatSize, contentDescription = "Reading settings")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(Icons.Outlined.Timer, contentDescription = null, tint = palette.text)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                formatMinutes(balanceSeconds),
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.text
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.background,
                        titleContentColor = palette.text,
                        navigationIconContentColor = palette.text,
                        actionIconContentColor = palette.text
                    )
                )
            },
            bottomBar = {
                ReaderBottomBar(
                    palette = palette,
                    sessionSeconds = sessionSeconds,
                    creditedSeconds = creditedSeconds,
                    progress = progress,
                    guardState = guardState
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(palette.background)
            ) {
                when {
                    book == null -> CenteredMessage(
                        icon = { Icon(Icons.Outlined.MenuBook, null, tint = palette.text, modifier = Modifier.size(48.dp)) },
                        title = "No book to read yet.",
                        subtitle = "Download a book from Discover first.",
                        color = palette.text
                    )

                    book?.format == "epub" && chapters.isNotEmpty() && extractRoot != null -> EpubReader(
                        chapters = chapters,
                        chapterIndex = chapterIndex,
                        extractRoot = extractRoot!!,
                        settings = settings,
                        palette = palette,
                        onPrev = { vm.goToChapter(chapterIndex - 1) },
                        onNext = { vm.goToChapter(chapterIndex + 1) },
                        onScrolled = { vm.onUserScrolled() },
                        onProgress = { vm.onProgressChanged(it) }
                    )

                    book?.format == "txt" && textContent != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = settings.marginDp.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = textContent!!,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = txtFontFamily(settings.fontFamily),
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
                            color = palette.text
                        )
                    }

                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (readerError != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(readerError ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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

        if (guardState.showIdleGate) {
            IdleGate(onContinue = { vm.resumeAfterIdle() })
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onFontSize = vm::setFontSize,
            onLineHeight = vm::setLineHeight,
            onFontFamily = vm::setFontFamily,
            onTheme = vm::setTheme,
            onMargin = vm::setMargin,
            onDismiss = { showSettings = false }
        )
    }

    if (showToc) {
        TocSheet(
            chapters = chapters,
            chapterIndex = chapterIndex,
            onSelect = { i ->
                vm.goToChapter(i)
                showToc = false
            },
            onDismiss = { showToc = false }
        )
    }
}

@Composable
private fun ReaderBottomBar(
    palette: ReaderPalette,
    sessionSeconds: Long,
    creditedSeconds: Long,
    progress: Float,
    guardState: ReadingGuard.State
) {
    val status = when {
        guardState.tooFast -> "Paused · too fast"
        guardState.showIdleGate -> "Paused · idle"
        guardState.crediting -> "Earning"
        else -> "Paused"
    }
    val statusColor = when {
        guardState.tooFast -> MaterialTheme.colorScheme.error
        guardState.crediting -> MaterialTheme.colorScheme.primary
        else -> palette.secondary
    }
    Surface(color = palette.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Session ${formatClock(sessionSeconds)}", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
                Text("Counted ${formatClock(creditedSeconds)}", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    color: Color
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(subtitle, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IdleGate(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(32.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Still reading?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reading time paused because the page hasn't moved. Scroll to keep earning.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onContinue) { Text("Continue reading") }
            }
        }
    }
}

@Composable
private fun EpubReader(
    chapters: List<EpubChapter>,
    chapterIndex: Int,
    extractRoot: String,
    settings: ReaderSettings,
    palette: ReaderPalette,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrolled: () -> Unit,
    onProgress: (Float) -> Unit
) {
    val chapter = chapters[chapterIndex]
    // Key remember blocks on chapterIndex (guaranteed unique per chapter) rather than
    // on the EpubChapter object or File path, because single-file EPUBs produce
    // identical filePaths for every chapter.
    val chapterFile = remember(chapterIndex, extractRoot, chapter.filePath) {
        File(extractRoot, chapter.filePath)
    }
    val baseUrl = "file://${chapterFile.parentFile?.absolutePath ?: extractRoot}/"
    val html = remember(chapterIndex, chapterFile, settings, palette) {
        buildChapterHtml(chapterFile, settings, palette)
    }
    val anchor = chapter.anchor
    // renderKey MUST include chapterIndex so it always changes when the user navigates,
    // even for single-file EPUBs where every chapter shares the same filePath/baseUrl.
    val renderKey = "$chapterIndex|$baseUrl|${chapter.filePath}|${settings.fontSizeSp}|${settings.lineHeight}|${settings.fontFamily}|${settings.theme}|${settings.marginDp}"

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = chapterIndex > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous chapter", tint = palette.text)
            }
            Text(
                chapter.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondary
            )
            Text(
                "${chapterIndex + 1} / ${chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary
            )
            IconButton(onClick = onNext, enabled = chapterIndex < chapters.size - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next chapter", tint = palette.text)
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // JavaScript is enabled so anchor-based scrolling works for
                    // single-file EPUBs (spine items that share one HTML file).
                    this.settings.javaScriptEnabled = true
                    this.settings.allowFileAccess = true
                    this.settings.allowContentAccess = true
                    this.settings.builtInZoomControls = true
                    this.settings.displayZoomControls = false
                    setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        onScrolled()
                        val content = contentHeight.takeIf { it > 0 } ?: 1
                        val fraction = (scrollY.toFloat() / content).coerceIn(0f, 1f)
                        val overall = ((chapterIndex + fraction) / chapters.size).coerceIn(0f, 1f)
                        onProgress(overall)
                    }
                }
            },
            update = { webView ->
                if (webView.tag != renderKey) {
                    webView.tag = renderKey
                    // Clear any previously rendered content first so the user doesn't
                    // see the old chapter (e.g. the cover) lingering while the new
                    // HTML loads asynchronously.
                    webView.loadUrl("about:blank")
                    val targetAnchor = anchor
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view ?: return
                            // For single-file EPUBs, scroll to the chapter's anchor element.
                            // For multi-file EPUBs anchor is null and this is a no-op.
                            if (targetAnchor != null) {
                                val safeAnchor = targetAnchor
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\n", "")
                                    .replace("\r", "")
                                val js = """
                                    (function() {
                                        var el = document.getElementById('$safeAnchor');
                                        if (!el) {
                                            var els = document.getElementsByName('$safeAnchor');
                                            if (els.length > 0) el = els[0];
                                        }
                                        if (el) el.scrollIntoView(true);
                                    })();
                                """.trimIndent()
                                view.evaluateJavascript(js, null)
                            }
                        }
                    }
                    webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

private fun txtFontFamily(key: String): FontFamily = when (key) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Serif
}

private fun buildChapterHtml(file: File, settings: ReaderSettings, palette: ReaderPalette): String {
    val raw = runCatching { file.readText() }
        .getOrElse { "<html><body><p>Cannot load this chapter.</p></body></html>" }
    return injectCss(raw, buildCss(settings, palette))
}

private fun buildCss(settings: ReaderSettings, palette: ReaderPalette): String {
    val fontStack = fontStackFor(settings.fontFamily)
    val fontSizePx = settings.fontSizeSp.toInt()
    val marginPx = settings.marginDp.toInt()
    return """
        html, body { background-color: ${palette.bgHex} !important; color: ${palette.textHex} !important; }
        body { font-family: $fontStack !important; font-size: ${fontSizePx}px !important; line-height: ${settings.lineHeight} !important; margin: ${marginPx}px !important; padding: ${marginPx / 2}px !important; overflow: auto !important; }
        p, div, h1, h2, h3, h4, h5, h6, li, blockquote, span, td { color: ${palette.textHex} !important; }
        a { color: #4c8bf5 !important; }
        img { max-width: 100% !important; height: auto !important; }
    """.trimIndent()
}

private fun injectCss(html: String, css: String): String {
    val styleBlock = "<style>$css</style>"
    val headClose = Regex("</head>", RegexOption.IGNORE_CASE).find(html)
    val bodyClose = Regex("</body>", RegexOption.IGNORE_CASE).find(html)
    return when {
        headClose != null -> html.replaceRange(headClose.range.first, headClose.range.first, styleBlock)
        bodyClose != null -> html.replaceRange(bodyClose.range.first, bodyClose.range.first, styleBlock)
        else -> styleBlock + html
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onFontFamily: (String) -> Unit,
    onTheme: (String) -> Unit,
    onMargin: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Reading settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))

            Text("Text size — ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = settings.fontSizeSp, onValueChange = onFontSize, valueRange = 12f..32f)

            Text("Line spacing — ${"%.1f".format(settings.lineHeight)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = settings.lineHeight, onValueChange = onLineHeight, valueRange = 1f..2.2f)

            Text("Margins — ${settings.marginDp.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = settings.marginDp, onValueChange = onMargin, valueRange = 8f..48f)

            Text("Font", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("serif" to "Serif", "sans" to "Sans", "mono" to "Mono").forEach { (key, label) ->
                    SelectorPill(selected = settings.fontFamily == key, label = label) { onFontFamily(key) }
                }
            }

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderPalettes.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ThemeSwatch(selected = settings.theme == p.key, palette = p) { onTheme(p.key) }
                        Spacer(Modifier.height(4.dp))
                        Text(p.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    chapters: List<EpubChapter>,
    chapterIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(bottom = 24.dp)
        ) {
            itemsIndexed(chapters, key = { _, c -> c.filePath }) { i, chapter ->
                val selected = i == chapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectorPill(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun ThemeSwatch(selected: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(palette.background)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
