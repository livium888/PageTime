package com.pagetime.app.ui.screens.reader

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.R
import com.pagetime.app.data.local.MapMoment
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.data.learning.AiGenerationState
import com.pagetime.app.ui.formatClock
import com.pagetime.app.ui.formatMinutes
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref

private const val NAVIGATOR_TAG = "readium_navigator"

private enum class TapZone { CENTER }

@Composable
private fun MapMomentPrompt(
    moment: MapMoment,
    onExplore: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("A new map moment", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${moment.conceptCount} new ideas · ${moment.relationshipCount} connections",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            moment.featuredConcept?.let { concept ->
                Text(
                    moment.featuredRelationship?.let { "$concept $it" } ?: concept,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExplore) { Text("Explore in 2 minutes") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onOpenConcepts: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModelFactory(app, bookId))

    val book by vm.book.collectAsStateWithLifecycle()
    val textContent by vm.textContent.collectAsStateWithLifecycle()
    val initialTextFraction by vm.initialTextFraction.collectAsStateWithLifecycle()
    val initialTextOffset by vm.initialTextOffset.collectAsStateWithLifecycle()
    val sessionSeconds by vm.sessionSeconds.collectAsStateWithLifecycle()
    val creditedSeconds by vm.creditedSeconds.collectAsStateWithLifecycle()
    val balanceSeconds by vm.balanceSeconds.collectAsStateWithLifecycle()
    val readerError by vm.error.collectAsStateWithLifecycle()
    val guardState by vm.guardState.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val settings by vm.readerSettings.collectAsStateWithLifecycle()
    val publication by vm.publication.collectAsStateWithLifecycle()
    val initialLocatorJson by vm.initialLocatorJson.collectAsStateWithLifecycle()
    val initialLocatorReady by vm.initialLocatorReady.collectAsStateWithLifecycle()
    val bookmarkPresent by vm.bookmarkPresent.collectAsStateWithLifecycle()
    val resumeNotice by vm.resumeNotice.collectAsStateWithLifecycle()
    val aiGenerationState by vm.aiGenerationState.collectAsStateWithLifecycle()
    val mapMoment by vm.mapMoment.collectAsStateWithLifecycle()

    val palette = paletteFor(settings.theme)

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showCardSheet by remember { mutableStateOf(false) }
    var showChapterPrompt by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showMapMoment by remember { mutableStateOf(false) }
    var lastPromptedChapter by remember { mutableStateOf<Int?>(null) }
    // Show the chrome briefly on entry so the reader's options are discoverable;
    // it fades away automatically and can be recalled with a center tap.
    var controlsVisible by remember { mutableStateOf(true) }
    var textPageLabel by remember { mutableStateOf<String?>(null) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var chapterLabel by remember { mutableStateOf<String?>(null) }
    var epubRestoreFinished by remember { mutableStateOf(false) }
    var currentChapterHref by remember { mutableStateOf<String?>(null) }
    var sleepDeadline by remember { mutableStateOf<Long?>(null) }
    var txtGoRequest by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    var progressMode by remember { mutableStateOf(ProgressIndicatorMode.PERCENT) }
    var sleepRemainingMs by remember { mutableStateOf<Long?>(null) }
    // While the Kobo-style edge gesture is dragging, this preview overrides the
    // window brightness live; the saved setting is only written on drag end.
    var previewBrightness by remember { mutableStateOf<Float?>(null) }

    val tocEntries = remember(publication) {
        publication?.let { pub ->
            pub.tableOfContents.takeIf { it.isNotEmpty() }?.let { flattenToc(it) }
                ?: pub.readingOrder.mapIndexed { index, link ->
                    TocEntry(
                        title = link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                        depth = 0,
                        link = link
                    )
                }
        }
    }

    LaunchedEffect(mapMoment) {
        showMapMoment = mapMoment?.let { it.conceptCount > 0 || it.relationshipCount > 0 } == true
    }

    // Auto-hide the top controls after a short idle so reading becomes immersive.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(5_000)
        controlsVisible = false
    }

    // Kobo-style sleep timer: count down, then gracefully leave the book.
    LaunchedEffect(sleepDeadline) {
        sleepRemainingMs = null
        val deadline = sleepDeadline ?: return@LaunchedEffect
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                onBack()
                break
            }
            sleepRemainingMs = remaining
            delay(1_000)
        }
    }

    // Keep the screen on while the reader is in the foreground.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        val previous = rootView.keepScreenOn
        rootView.keepScreenOn = true
        onDispose { rootView.keepScreenOn = previous }
    }

    // Apply the reader's brightness only to this window, then restore the user's
    // device setting as soon as they leave the book. The edge-drag gesture feeds a
    // transient preview value so dragging dims the screen in real time before the
    // setting is saved.
    val window = (context as? Activity)?.window
    DisposableEffect(window, settings.brightness, previewBrightness) {
        if (window == null) {
            onDispose { }
        } else {
            val previousBrightness = window.attributes.screenBrightness
            val attributes = window.attributes
            attributes.screenBrightness = previewBrightness
                ?: settings.brightness
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attributes
            onDispose {
                val restored = window.attributes
                restored.screenBrightness = previousBrightness
                window.attributes = restored
            }
        }
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
        }
    }

    // The reading surface always owns the full viewport. Controls float above it,
    // so showing or hiding them never changes pagination or scroll geometry.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        when {
            book == null -> CenteredMessage(
                icon = { Icon(Icons.Outlined.MenuBook, null, tint = palette.text, modifier = Modifier.size(48.dp)) },
                title = "No book to read yet.",
                subtitle = "Download a book from Discover first.",
                color = palette.text
            )

            publication != null -> ReadiumNavigatorHost(
                publication = publication!!,
                initialLocatorReady = initialLocatorReady,
                initialLocatorJson = initialLocatorJson,
                settings = settings,
                onLocatorChanged = { locator ->
                    vm.onLocatorChanged(locator)
                    publication?.let { pub ->
                        currentChapterHref = locator.href.toString()
                        val idx = pub.readingOrder.indexOfFirstWithHref(locator.href)
                        val size = pub.readingOrder.size
                        if (idx != null && size > 0) {
                            chapterLabel = "${idx + 1} of $size"
                            if (epubRestoreFinished &&
                                (locator.locations?.progression?.toFloat() ?: 0f) >= 0.98f &&
                                lastPromptedChapter != idx
                            ) {
                                lastPromptedChapter = idx
                                controlsVisible = true
                                showChapterPrompt = true
                                vm.onChapterCompleted(
                                    chapterIndex = idx,
                                    locatorJson = locator.toJSON().toString(),
                                    textFraction = ((idx + 1f) / size).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                },
                onTapZone = { controlsVisible = !controlsVisible },
                onNavigatorChanged = { navigator = it },
                onRestoreComplete = {
                    vm.markEpubRestoreComplete()
                    epubRestoreFinished = true
                }
            )

            book?.format == "txt" && textContent != null -> TextReaderHost(
                content = textContent!!,
                initialFraction = initialTextFraction,
                initialOffset = initialTextOffset,
                settings = settings,
                palette = palette,
                goRequest = txtGoRequest,
                onPageChanged = { page, pageCount, pageStartOffset, userInitiated ->
                    textPageLabel = "Page ${page + 1} of $pageCount"
                    vm.onTextPageChanged(page, pageCount, userInitiated, pageStartOffset)
                },
                onRestoreComplete = vm::markTxtRestoreComplete,
                onToggleChrome = { controlsVisible = !controlsVisible }
            )

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

        // Kobo-style left-edge gesture: drag vertically to dim or brighten the
        // book without opening any menu. Lives under the chrome so the back button
        // and bars always stay responsive.
        if (book != null && (publication != null || textContent != null)) {
            BrightnessSwipeOverlay(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(44.dp),
                currentBrightness = settings.brightness,
                onDrag = { previewBrightness = it },
                onDragEnd = {
                    previewBrightness = null
                    vm.setReaderBrightness(it)
                },
                onToggleChrome = { controlsVisible = !controlsVisible }
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ReaderTopBar(
                title = book?.title ?: "Reading",
                hasChapters = tocEntries != null,
                hasGoTo = book?.format == "txt" && textContent != null,
                balanceSeconds = balanceSeconds,
                bookmarkPresent = bookmarkPresent,
                sleepRemainingSeconds = sleepRemainingMs?.let { (it / 1000).toInt().coerceAtLeast(0) },
                palette = palette,
                onBack = onBack,
                onToc = { showToc = true },
                onGoTo = { showGoTo = true },
                onStats = { showStats = true },
                onSleepTimer = { showSleepTimer = true },
                onBookmark = vm::toggleBookmark,
                onCreateCard = { showCardSheet = true },
                onSettings = { showSettings = true }
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (showChapterPrompt) {
                    ChapterReviewPrompt(
                        chapterLabel = chapterLabel ?: "this chapter",
                        onCreateCard = {
                            showChapterPrompt = false
                            showCardSheet = true
                        },
                        onDismiss = { showChapterPrompt = false }
                    )
                }
                ReaderBottomBar(
                    palette = palette,
                    sessionSeconds = sessionSeconds,
                    creditedSeconds = creditedSeconds,
                    progress = progress,
                    guardState = guardState,
                    chapterLabel = chapterLabel,
                    pageLabel = textPageLabel,
                    chapterCount = publication?.readingOrder?.size,
                    mode = progressMode,
                    onModeToggle = {
                        progressMode = if (progressMode == ProgressIndicatorMode.PERCENT) {
                            ProgressIndicatorMode.TIME_LEFT
                        } else {
                            ProgressIndicatorMode.PERCENT
                        }
                    }
                )
            }
        }

        if (guardState.showIdleGate) {
            IdleGate(onContinue = { vm.resumeAfterIdle() })
        }

        if (resumeNotice != null) {
            ResumeNotice(text = resumeNotice!!)
        }

        if (showMapMoment) {
            mapMoment?.let { moment ->
                MapMomentPrompt(
                    moment = moment,
                    onExplore = {
                        showMapMoment = false
                        onOpenConcepts(moment.bookId)
                    },
                    onDismiss = { showMapMoment = false }
                )
            }
        }

        when (val state = aiGenerationState) {
            AiGenerationState.Generating -> AiGenerationNotice("Creating recall cards…")
            is AiGenerationState.Generated -> if (state.count > 0) {
                AiGenerationNotice("${state.count} recall card${if (state.count == 1) "" else "s"} ready")
            }
            is AiGenerationState.Failed -> AiGenerationNotice("Automatic cards unavailable")
            AiGenerationState.Disabled, AiGenerationState.Idle -> Unit
        }
    }

    if (showCardSheet) {
        CardCreationSheet(
            bookTitle = book?.title ?: "Book",
            chapterLabel = chapterLabel,
            onSave = { prompt, answer, explanation ->
                vm.createLearningCard(prompt, answer, explanation, chapterLabel)
                showCardSheet = false
            },
            onDismiss = { showCardSheet = false }
        )
    }

    if (showSettings) {
        ReaderAppearanceSheet(
            settings = settings,
            onApply = vm::applyReaderSettings,
            onDismiss = { showSettings = false }
        )
    }

    if (showToc && tocEntries != null) {
        TocSheet(
            entries = tocEntries,
            currentHref = currentChapterHref,
            onSelect = { entry ->
                navigator?.go(entry.link)
                showToc = false
            },
            onDismiss = { showToc = false }
        )
    }

    if (showSleepTimer) {
        SleepTimerSheet(
            currentRemainingSeconds = sleepRemainingMs?.let { (it / 1000).toInt() },
            onPick = { minutes ->
                sleepDeadline = if (minutes == null) {
                    null
                } else {
                    SystemClock.elapsedRealtime() + minutes * 60_000L
                }
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false }
        )
    }

    if (showStats) {
        ReadingStatsSheet(
            bookTitle = book?.title ?: "Book",
            sessionSeconds = sessionSeconds,
            creditedSeconds = creditedSeconds,
            progress = progress,
            balanceSeconds = balanceSeconds,
            onDismiss = { showStats = false }
        )
    }

    if (showGoTo && book?.format == "txt" && textContent != null) {
        GoToSheet(
            progress = progress,
            onSeek = { fraction ->
                txtGoRequest = fraction to SystemClock.elapsedRealtime()
                showGoTo = false
            },
            onDismiss = { showGoTo = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextReaderHost(
    content: String,
    initialFraction: Float,
    initialOffset: Int,
    settings: ReaderSettings,
    palette: ReaderPalette,
    goRequest: Pair<Float, Long>?,
    onPageChanged: (page: Int, pageCount: Int, pageStartOffset: Int, userInitiated: Boolean) -> Unit,
    onRestoreComplete: () -> Unit,
    onToggleChrome: () -> Unit
) {
    // Pages re-paginate when typography changes so the same fraction of the book
    // stays in view and the text always fits the screen at the new setting.
    val pages = remember(content, settings.fontSizeSp, settings.lineHeight, settings.marginDp) {
        TextPageLayout.paginate(content, TextPageLayout.targetCharsFor(settings))
    }
    var lastFraction by remember { mutableStateOf(0f) }
    val initialPage = remember(pages, initialFraction, initialOffset) {
        val offset = if (initialOffset > 0) initialOffset else {
            (initialFraction.coerceIn(0f, 1f) * content.length).toInt()
        }
        TextPageLayout.pageForOffset(pages, offset)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pages.size }
    )

    LaunchedEffect(pagerState, pages) {
        if (lastFraction > 0f && pages.isNotEmpty() && pagerState.currentPage != TextPageLayout.pageForFraction(pages, lastFraction)) {
            pagerState.scrollToPage(TextPageLayout.pageForFraction(pages, lastFraction))
        }
        onPageChanged(
            pagerState.currentPage,
            pages.size,
            pages[pagerState.currentPage].startOffset,
            false
        )
        onRestoreComplete()
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                lastFraction = TextPageLayout.fractionForPage(page, pages.size)
                if (scrolling) {
                    onPageChanged(page, pages.size, pages[page].startOffset, true)
                }
            }
    }

    // Kindle-style "Go to position": jump straight to the page matching a fraction.
    LaunchedEffect(goRequest) {
        val (fraction, _) = goRequest ?: return@LaunchedEffect
        if (pages.isNotEmpty()) {
            lastFraction = fraction
            pagerState.scrollToPage(TextPageLayout.pageForFraction(pages, fraction))
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = true
    ) { pageIndex ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val centerStart = size.width / 3f
                        val centerEnd = size.width * 2f / 3f
                        if (offset.x in centerStart..centerEnd) onToggleChrome()
                    }
                }
                .padding(horizontal = settings.marginDp.dp, vertical = 24.dp)
                .background(palette.background)
        ) {
            Text(
                text = pages[pageIndex].text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    letterSpacing = (settings.fontSizeSp * 0.015f).sp
                ),
                fontFamily = readerFontFamily(settings.fontFamily),
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
                textAlign = if (settings.alignment == "justify") TextAlign.Justify else TextAlign.Start,
                color = palette.text
            )
        }
    }
}

/**
 * Hosts the Readium [EpubNavigatorFragment] — the battle-tested open-source EPUB
 * engine — inside the Compose tree. Readium handles pagination, position tracking
 * (exact locators persisted per book) and rendering; this composable only wires
 * the navigator into the activity's FragmentManager and forwards events.
 */
@OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)
@Composable
private fun ReadiumNavigatorHost(
    publication: Publication,
    initialLocatorReady: Boolean,
    initialLocatorJson: String?,
    settings: ReaderSettings,
    onLocatorChanged: (Locator) -> Unit,
    onTapZone: (TapZone) -> Unit,
    onNavigatorChanged: (EpubNavigatorFragment?) -> Unit,
    onRestoreComplete: () -> Unit
) {
    val context = LocalContext.current
    val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager

    val currentOnLocator by rememberUpdatedState(onLocatorChanged)
    val currentOnTapZone by rememberUpdatedState(onTapZone)

    var container by remember { mutableStateOf<FrameLayout?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                id = R.id.readium_container
            }.also { container = it }
        }
    )

    LaunchedEffect(fragmentManager, container, publication, initialLocatorReady, initialLocatorJson) {
        val fm = fragmentManager ?: return@LaunchedEffect
        if (!initialLocatorReady) return@LaunchedEffect
        val frame = container ?: return@LaunchedEffect
        if (fm.findFragmentByTag(NAVIGATOR_TAG) != null) return@LaunchedEffect

        // The FragmentManager resolves the container view by ID at commit time, so
        // wait until the Compose-hosted FrameLayout is attached to the window.
        var waited = 0
        while (!frame.isAttachedToWindow && waited < 4_000) {
            delay(50)
            waited += 50
        }
        if (!frame.isAttachedToWindow) return@LaunchedEffect

        val initialLocator = initialLocatorJson?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        }

        // A tap only toggles the chrome. Page movement is reserved for Readium's
        // deliberate swipe/pagination gestures; accidental edge taps cannot turn pages.
        val inputListener = object : InputListener {
            override fun onTap(@Suppress("UNUSED_PARAMETER") event: TapEvent): Boolean {
                if (ReaderInteractionPolicy.togglesChromeOnTap()) {
                    currentOnTapZone(TapZone.CENTER)
                }
                return true
            }
        }

        try {
            val navigatorFactory = EpubNavigatorFactory(publication)
            fm.fragmentFactory = navigatorFactory.createFragmentFactory(
                initialLocator = initialLocator,
                initialPreferences = readiumPreferences(settings),
                listener = null
            )
            fm.commitNow {
                add(R.id.readium_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            return@LaunchedEffect
        }

        val nav = fm.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
            ?: return@LaunchedEffect
        nav.addInputListener(inputListener)
        onNavigatorChanged(nav)

        try {
            var firstLocator = true
            nav.currentLocator.collect {
                currentOnLocator(it)
                if (firstLocator) {
                    firstLocator = false
                    onRestoreComplete()
                }
            }
        } finally {
            runCatching {
                nav.removeInputListener(inputListener)
                fm.beginTransaction().remove(nav).commitNowAllowingStateLoss()
            }
            onNavigatorChanged(null)
        }
    }

    // Apply reading preferences initially and whenever they change.
    LaunchedEffect(settings) {
        (fragmentManager?.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment)
            ?.submitPreferences(readiumPreferences(settings))
    }
}

/** Maps the app's reader settings onto Readium EPUB preferences. */
@OptIn(ExperimentalReadiumApi::class)
private fun readiumPreferences(s: ReaderSettings): EpubPreferences = EpubPreferences(
        // Readium's font size is a multiplier where 1.0 = 100%, not a percentage.
        fontSize = s.fontSizeSp / 16.0,
        lineHeight = s.lineHeight.toDouble(),
        pageMargins = s.marginDp / 16.0,
    fontFamily = when (s.fontFamily) {
        "sans" -> ReadiumFontFamily.SANS_SERIF
        "mono" -> ReadiumFontFamily.MONOSPACE
        // Literata is bundled for the plain-text reader; Readium 3.0.0 exposes
        // no custom-font hook, so EPUBs fall back to its built-in serif stack.
        else -> ReadiumFontFamily.SERIF
    },
    theme = when (s.theme) {
        "dark", "night" -> Theme.DARK
        "sepia" -> Theme.SEPIA
        else -> Theme.LIGHT
    },
    publisherStyles = false,
    scroll = false
)

private data class TocEntry(val title: String, val depth: Int, val link: Link)

private fun flattenToc(links: List<Link>, depth: Int = 0, out: MutableList<TocEntry> = mutableListOf()): List<TocEntry> {
    for (link in links) {
        out.add(TocEntry(title = link.title ?: link.href.toString(), depth = depth, link = link))
        if (link.children.isNotEmpty()) flattenToc(link.children, depth + 1, out)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    hasChapters: Boolean,
    hasGoTo: Boolean,
    balanceSeconds: Long,
    bookmarkPresent: Boolean,
    sleepRemainingSeconds: Int?,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onGoTo: () -> Unit,
    onStats: () -> Unit,
    onSleepTimer: () -> Unit,
    onBookmark: () -> Unit,
    onCreateCard: () -> Unit,
    onSettings: () -> Unit
) {
    var optionsExpanded by remember { mutableStateOf(false) }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = palette.text)
                Spacer(Modifier.width(4.dp))
                Text(
                    formatMinutes(balanceSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text
                )
            }
            if (sleepRemainingSeconds != null) {
                TextButton(onClick = onSleepTimer) {
                    Text(
                        "Sleep ${formatTimerSeconds(sleepRemainingSeconds)}",
                        color = palette.text,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Box {
                TextButton(onClick = { optionsExpanded = true }) {
                    Text("Options")
                }
                DropdownMenu(
                    expanded = optionsExpanded,
                    onDismissRequest = { optionsExpanded = false }
                ) {
                    if (hasChapters) {
                        DropdownMenuItem(
                            text = { Text("Chapters") },
                            leadingIcon = { Icon(Icons.Filled.List, contentDescription = null) },
                            onClick = {
                                optionsExpanded = false
                                onToc()
                            }
                        )
                    }
                    if (hasGoTo) {
                        DropdownMenuItem(
                            text = { Text("Go to position") },
                            leadingIcon = { Icon(Icons.Outlined.FindInPage, contentDescription = null) },
                            onClick = {
                                optionsExpanded = false
                                onGoTo()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (bookmarkPresent) "Remove bookmark" else "Bookmark this position") },
                        leadingIcon = {
                            Icon(
                                if (bookmarkPresent) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            optionsExpanded = false
                            onBookmark()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Create recall card") },
                        leadingIcon = { Icon(Icons.Outlined.School, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onCreateCard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reading statistics") },
                        leadingIcon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onStats()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Appearance (Aa)") },
                        leadingIcon = { Icon(Icons.Filled.FormatSize, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sleep timer") },
                        leadingIcon = { Icon(Icons.Outlined.NightsStay, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onSleepTimer()
                        }
                    )
                }
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
    chapterLabel: String?,
    pageLabel: String?,
    chapterCount: Int?,
    mode: ProgressIndicatorMode,
    onModeToggle: () -> Unit
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
    val indicatorText = when (mode) {
        ProgressIndicatorMode.PERCENT -> "$percent%"
        ProgressIndicatorMode.TIME_LEFT ->
            estimatedTimeLeft(progress, creditedSeconds) ?: "$percent%"
    }
    Surface(color = palette.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Tapping anywhere on the bar cycles the indicator between
            // percentage and estimated time left, like Kindle.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onModeToggle)
            ) {
                Column {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Kindle-style chapter markers along the progress line.
                    if (chapterCount != null && chapterCount > 1) {
                        Spacer(Modifier.height(3.dp))
                        Row(Modifier.fillMaxWidth().height(5.dp)) {
                            repeat(chapterCount.coerceAtMost(60)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 1.dp)
                                        .fillMaxHeight()
                                        .background(palette.text.copy(alpha = 0.22f))
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        chapterLabel != null -> "Chapter $chapterLabel"
                        pageLabel != null -> pageLabel
                        else -> "Reading"
                    },
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onModeToggle,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        indicatorText,
                        color = palette.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Session ${formatClock(sessionSeconds)}", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
                Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
                Text("Counted ${formatClock(creditedSeconds)}", color = palette.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResumeNotice(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun AiGenerationNotice(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp
        ) {
            Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    entries: List<TocEntry>,
    currentHref: String?,
    onSelect: (TocEntry) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(bottom = 24.dp)
        ) {
            itemsIndexed(entries, key = { i, _ -> i }) { _, entry ->
                val isCurrent = currentHref != null && entry.link.href.toString() == currentHref
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(
                            start = (20 + entry.depth * 20).dp,
                            end = 20.dp,
                            top = 14.dp,
                            bottom = 14.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    if (isCurrent) {
                        Text(
                            "You are here",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private enum class ProgressIndicatorMode { PERCENT, TIME_LEFT }

private fun formatTimerSeconds(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** Kindle-style "time left in book" derived from the user's own reading pace. */
private fun estimatedTimeLeft(progress: Float, creditedSeconds: Long): String? {
    if (creditedSeconds < 60L || progress <= 0.01f || progress >= 0.995f) return null
    val speed = progress / creditedSeconds.toFloat().coerceAtLeast(1f)
    val remainingSeconds = ((1f - progress) / speed).toLong()
    return when {
        remainingSeconds < 60L -> "~${remainingSeconds}s left"
        remainingSeconds < 3600L -> "~${remainingSeconds / 60}m left"
        else -> "~${remainingSeconds / 3600}h ${(remainingSeconds % 3600) / 60}m left"
    }
}

/**
 * Kobo-style left-edge gesture: a vertical drag on the edge of the book dims or
 * brightens the screen immediately, and the choice is saved on release. Only the
 * thin strip participates, so page swipes anywhere else are untouched. Lives below
 * the chrome so the top bar and back button always stay responsive.
 */
@Composable
private fun BrightnessSwipeOverlay(
    currentBrightness: Float?,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf(false) }
    var dragBrightness by remember { mutableStateOf(1f) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { onToggleChrome() }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        active = true
                        dragBrightness = currentBrightness ?: 1f
                    },
                    onDragEnd = {
                        if (active) {
                            active = false
                            onDragEnd(dragBrightness)
                        }
                    },
                    onDragCancel = { active = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (!active) {
                            active = true
                            dragBrightness = currentBrightness ?: 1f
                        }
                        val heightPx = size.height.toFloat().coerceAtLeast(1f)
                        dragBrightness = (dragBrightness - dragAmount / heightPx)
                            .coerceIn(0.15f, 1f)
                        onDrag(dragBrightness)
                    }
                )
            }
    ) {
        if (active) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 64.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Brightness5,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("${(dragBrightness * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    currentRemainingSeconds: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            Text(
                "The book closes gently when the timer ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (currentRemainingSeconds != null) {
                TextButton(onClick = { onPick(null) }) {
                    Text("Cancel timer · ${formatTimerSeconds(currentRemainingSeconds)} left")
                }
            }
            listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(minutes) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.NightsStay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (minutes == 5) "5 minutes" else "$minutes minutes",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToSheet(
    progress: Float,
    onSeek: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var fraction by remember { mutableStateOf(progress.coerceIn(0f, 1f)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Go to position", style = MaterialTheme.typography.titleLarge)
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = fraction,
                onValueChange = { fraction = it },
                valueRange = 0f..1f
            )
            Button(onClick = { onSeek(fraction) }, modifier = Modifier.fillMaxWidth()) {
                Text("Go")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingStatsSheet(
    bookTitle: String,
    sessionSeconds: Long,
    creditedSeconds: Long,
    progress: Float,
    balanceSeconds: Long,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Reading statistics", style = MaterialTheme.typography.titleLarge)
            Text(
                bookTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            StatRow("This session", formatClock(sessionSeconds))
            StatRow("Time earned", formatClock(creditedSeconds))
            StatRow("Book progress", "${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
            estimatedTimeLeft(progress, creditedSeconds)?.let {
                StatRow("Estimated time left", it)
            }
            StatRow("Balance", formatMinutes(balanceSeconds))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
