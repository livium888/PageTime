package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.R
import com.pagetime.app.data.library.EpubChapter
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.ui.formatClock
import com.pagetime.app.ui.formatMinutes
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Stable keys (resource IDs from res/values/ids.xml) used to remember the press
// origin between the ACTION_DOWN and ACTION_UP of a tap on the reading surface.
// These MUST be declared resource ids: View.setTag(int, Object) rejects any other
// key with "IllegalArgumentException: The key must be an application-specific
// resource id" — View.generateViewId() values are NOT accepted and crash on the
// first touch of the reading surface.
private val ReaderTapDownX = R.id.reader_down_x
private val ReaderTapDownY = R.id.reader_down_y
private val ReaderTapDownT = R.id.reader_down_t
private val ReaderAutoAdvanceArmed = R.id.reader_auto_advance_armed

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
    val epubScrollProgress by vm.epubScrollProgress.collectAsStateWithLifecycle()

    val palette = paletteFor(settings.theme)
    val scrollState = rememberScrollState()

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Auto-hide the top controls after a short idle so reading becomes immersive.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(5_000)
        controlsVisible = false
    }

    // Keep the screen on while the reader is in the foreground.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        val previous = rootView.keepScreenOn
        rootView.keepScreenOn = true
        onDispose { rootView.keepScreenOn = previous }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            // Top controls — hidden after idle, revealed with a tap.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                ReaderTopBar(
                    title = book?.title ?: "Reading",
                    hasChapters = chapters.isNotEmpty(),
                    balanceSeconds = balanceSeconds,
                    palette = palette,
                    onBack = onBack,
                    onToc = { showToc = true },
                    onSettings = { showSettings = true }
                )
            }

            // Main reading surface.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                        scrollProgress = epubScrollProgress,
                        onPrev = { vm.goToChapter(vm.chapterIndex.value - 1) },
                        onNext = { vm.goToChapter(vm.chapterIndex.value + 1) },
                        onScrolled = { vm.onUserScrolled() },
                        onProgress = { vm.onProgressChanged(it) },
                        onScrollProgressChanged = { vm.saveEpubScrollProgress(it) },
                        onToggleControls = { controlsVisible = !controlsVisible }
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

            // Bottom progress / status HUD — always visible so reading time is transparent.
            ReaderBottomBar(
                palette = palette,
                sessionSeconds = sessionSeconds,
                creditedSeconds = creditedSeconds,
                progress = progress,
                guardState = guardState,
                chapterLabel = if (book?.format == "epub" && chapters.isNotEmpty())
                    "${chapterIndex + 1} of ${chapters.size}" else null
            )
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

private fun currentTimeLabel(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    hasChapters: Boolean,
    balanceSeconds: Long,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit
) {
    var nowLabel by remember { mutableStateOf(currentTimeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowLabel = currentTimeLabel()
        }
    }
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            Text(
                nowLabel,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (hasChapters) {
                IconButton(onClick = onToc) {
                    Icon(Icons.Filled.List, contentDescription = "Chapters")
                }
            }
            IconButton(onClick = onSettings) {
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
}

@Composable
private fun ReaderBottomBar(
    palette: ReaderPalette,
    sessionSeconds: Long,
    creditedSeconds: Long,
    progress: Float,
    guardState: ReadingGuard.State,
    chapterLabel: String?
) {
    val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (chapterLabel != null) "Chapter $chapterLabel" else "Reading",
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "$percent%",
                    color = palette.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
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
    scrollProgress: Float,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrolled: () -> Unit,
    onProgress: (Float) -> Unit,
    onScrollProgressChanged: (Float) -> Unit,
    onToggleControls: () -> Unit
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

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // JavaScript is enabled so anchor-based scrolling and fractional
                // position restore work for EPUB chapters.
                this.settings.javaScriptEnabled = true
                this.settings.allowFileAccess = true
                this.settings.allowContentAccess = true
                this.settings.builtInZoomControls = true
                this.settings.displayZoomControls = false
            }
        },
        update = { webView ->
            // --- Tap zones (left = prev chapter, right = next, center = toggle HUD).
            // Rebound here on every recomposition so the captured callbacks always
            // reference the current chapter index and visibility state.
            webView.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        webView.setTag(ReaderTapDownX, event.x)
                        webView.setTag(ReaderTapDownY, event.y)
                        webView.setTag(ReaderTapDownT, System.currentTimeMillis())
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - (webView.getTag(ReaderTapDownX) as? Float ?: 0f)
                        val dy = event.y - (webView.getTag(ReaderTapDownY) as? Float ?: 0f)
                        val dt = System.currentTimeMillis() - (webView.getTag(ReaderTapDownT) as? Long ?: 0L)
                        val isTap = abs(dx) < 24f && abs(dy) < 24f && dt < 600L
                        if (isTap && view.width > 0) {
                            when {
                                event.x < view.width / 3f -> onPrev()
                                event.x > view.width * 2f / 3f -> onNext()
                                else -> onToggleControls()
                            }
                            return@setOnTouchListener false
                        }
                        // --- Auto-advance at end of chapter: a deliberate upward
                        // swipe while already scrolled to the bottom loads the next
                        // chapter. Touch events are used instead of overscroll
                        // callbacks because WebView's scrolling is driven by
                        // Chromium and onOverScrolled is not reliably called.
                        // Works on chapters shorter than one screen too, where
                        // maxScroll == 0 and scrollY == 0.
                        val swipedUp = dy < -60f && dt < 800L
                        val armed = webView.getTag(ReaderAutoAdvanceArmed) as? Boolean == true
                        if (swipedUp && armed) {
                            val maxScroll =
                                (webView.contentHeight - webView.height).coerceAtLeast(0)
                            if (webView.scrollY >= maxScroll - 8) {
                                webView.setTag(ReaderAutoAdvanceArmed, false)
                                onNext()
                            }
                        }
                    }
                }
                false
            }

            // --- Scroll feedback (anti-cheat) + position persistence.
            // The fraction denominator MUST match the restore math: fraction is of
            // the *scrollable* distance (content minus viewport), not full content
            // height. Mismatched denominators made restored positions land wrong.
            webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                onScrolled()
                val maxScroll = (webView.contentHeight - webView.height).coerceAtLeast(1)
                val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
                val overall = ((chapterIndex + fraction) / chapters.size).coerceIn(0f, 1f)
                onProgress(overall)
                onScrollProgressChanged(fraction)
            }

            // --- Load content, but ONLY when the chapter/settings actually changed so
            // we don't reset scroll position on every recomposition.
            if (webView.tag != renderKey) {
                webView.tag = renderKey
                webView.loadUrl("about:blank")
                val targetAnchor = anchor
                val restoreFraction = scrollProgress
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        if (url == "about:blank") return
                        //
                        // Position restore, done the way reader apps actually do it:
                        // chapter layout is NOT stable when onPageFinished fires
                        // (fonts/images still load, scrollHeight keeps growing), so a
                        // single scrollTo lands at the wrong spot. Instead, a JS loop
                        // re-applies the target for ~4.5s until layout settles, and
                        // backs off permanently the moment the user scrolls somewhere
                        // other than where we put them (>40px from our last apply).
                        //
                        if (targetAnchor != null) {
                            // Single-file EPUB: scroll to this chapter's anchor element.
                            val safeAnchor = targetAnchor
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "")
                                .replace("\r", "")
                            val js = """
                                (function() {
                                    var n = 0;
                                    var id = setInterval(function() {
                                        var el = document.getElementById('$safeAnchor');
                                        if (!el) {
                                            var els = document.getElementsByName('$safeAnchor');
                                            if (els.length > 0) el = els[0];
                                        }
                                        if (el) {
                                            if (window.__ptLast != null &&
                                                Math.abs(window.scrollY - window.__ptLast) > 40) {
                                                clearInterval(id); return;
                                            }
                                            el.scrollIntoView(true);
                                            window.__ptLast = window.scrollY;
                                        }
                                        if (++n >= 30) clearInterval(id);
                                    }, 150);
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        } else if (restoreFraction > 0f) {
                            // Multi-file EPUB: jump to the saved fraction of the chapter.
                            val js = """
                                (function() {
                                    var target = $restoreFraction;
                                    var n = 0;
                                    var id = setInterval(function() {
                                        if (window.__ptLast != null &&
                                            Math.abs(window.scrollY - window.__ptLast) > 40) {
                                            clearInterval(id); return;
                                        }
                                        var max = document.documentElement.scrollHeight - window.innerHeight;
                                        if (max < 0) max = 0;
                                        var pos = Math.round(max * target);
                                        if (pos > 0) {
                                            window.scrollTo(0, pos);
                                            window.__ptLast = pos;
                                        }
                                        if (++n >= 30) clearInterval(id);
                                    }, 150);
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        }

                        // Arm auto-advance only after the page settles: the position-
                        // restore scroll itself must never count as "reached the end".
                        // (Restore generates no touch events anyway — this is belt and
                        // braces for restores that land at the very bottom.)
                        view.setTag(ReaderAutoAdvanceArmed, false)
                        view.postDelayed({ view.setTag(ReaderAutoAdvanceArmed, true) }, 500)
                    }
                }
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun txtFontFamily(key: String): FontFamily = when (key) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Serif
}

private fun buildChapterHtml(file: File, settings: ReaderSettings, palette: ReaderPalette): String {
    // Read with explicit UTF-8. runCatching ensures the reader shows a graceful
    // message instead of crashing if the chapter file is corrupt or unreadable.
    val raw = runCatching { file.readText(Charsets.UTF_8) }
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

            Text("Line spacing — ${
                "%.1f".format(settings.lineHeight)
            }", style = MaterialTheme.typography.bodyMedium)
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
            // Index-based keys: path+anchor can repeat for single-file EPUBs,
            // and duplicate LazyColumn keys throw at runtime.
            itemsIndexed(chapters, key = { i, _ -> i }) { i, chapter ->
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
