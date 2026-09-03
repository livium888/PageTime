package com.pagetime.app.ui.screens.reader

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.pagetime.app.data.LumenAddress
import com.pagetime.app.data.LumenDraft
import com.pagetime.app.data.local.LumenCardEntity
import com.pagetime.app.data.local.MapMoment
import com.pagetime.app.data.local.ReaderSettings
import com.pagetime.app.ui.formatClock
import com.pagetime.app.ui.formatMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign
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
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenConcepts: (String) -> Unit = {},
    onExplainBack: (bookId: String, chapterIndex: Int, chapterTitle: String, bookTitle: String, locatorJson: String?, textOffset: Int?) -> Unit = { _, _, _, _, _, _ -> },
    onOpenLumenCards: (String) -> Unit = {}
) {
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
    val checkpointPresent by vm.checkpointPresent.collectAsStateWithLifecycle()
    val enhancing by vm.enhancing.collectAsStateWithLifecycle()
    val enhancementProgress by vm.enhancementProgress.collectAsStateWithLifecycle()
    val resumeNotice by vm.resumeNotice.collectAsStateWithLifecycle()
    val mapMoment by vm.mapMoment.collectAsStateWithLifecycle()
    val conceptMap by vm.conceptMap.collectAsStateWithLifecycle()
    val lumenDraft by vm.lumenDraft.collectAsStateWithLifecycle()
    val lumenCapturing by vm.lumenCapturing.collectAsStateWithLifecycle()
    val lumenFileSuggestions by vm.lumenFileSuggestions.collectAsStateWithLifecycle()

    val palette = paletteFor(settings.theme)

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showChapterReviewPrompt by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showMapMoment by remember { mutableStateOf(false) }
    // Show the chrome briefly on entry so the reader's options are discoverable;
    // it fades away automatically and can be recalled with a center tap.
    var controlsVisible by remember { mutableStateOf(true) }
    var textPageLabel by remember { mutableStateOf<String?>(null) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var chapterLabel by remember { mutableStateOf<String?>(null) }
    var currentChapterHref by remember { mutableStateOf<String?>(null) }
    var currentChapterIndex by remember { mutableStateOf<Int?>(null) }
    var currentLocator by remember { mutableStateOf<Locator?>(null) }
    var activeConceptId by remember { mutableStateOf<String?>(null) }
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
                    currentLocator = locator
                    vm.onLocatorChanged(locator)
                    publication?.let { pub ->
                        currentChapterHref = locator.href.toString()
                        val idx = pub.readingOrder.indexOfFirstWithHref(locator.href)
                        currentChapterIndex = idx
                        val size = pub.readingOrder.size
                        if (idx != null && size > 0) {
                            chapterLabel = "${idx + 1} of $size"
                        }
                    }
                },
                onTapZone = { controlsVisible = !controlsVisible },
                onNavigatorChanged = { navigator = it },
                onRestoreComplete = {
                    vm.markEpubRestoreComplete()
                }
            )

            book?.format == "txt" && textContent != null -> TextReaderHost(
                content = textContent!!,
                initialFraction = initialTextFraction,
                initialOffset = initialTextOffset,
                settings = settings,
                palette = palette,
                concepts = conceptMap.concepts,
                conceptLevel = settings.conceptHints,
                activeConceptId = activeConceptId,
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

        if (publication != null) {
            EpubConceptDecorationLayer(
                navigator = navigator,
                concepts = conceptMap.concepts,
                chapterIndex = currentChapterIndex,
                currentLocator = currentLocator,
                level = settings.conceptHints,
                onConceptActivated = { activeConceptId = it }
            )
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
                checkpointPresent = checkpointPresent,
                sleepRemainingSeconds = sleepRemainingMs?.let { (it / 1000).toInt().coerceAtLeast(0) },
                palette = palette,
                onBack = onBack,
                onToc = { showToc = true },
                onGoTo = { showGoTo = true },
                onStats = { showStats = true },
                onSleepTimer = { showSleepTimer = true },
                onBookmark = vm::toggleBookmark,
                onSettings = { showSettings = true },
                onCopyTranscript = if (textContent != null) {
                    {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(book?.title ?: "Transcript", textContent!!))
                    }
                } else null,
                onShareTranscript = if (textContent != null) {
                    {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, book?.title ?: "Transcript")
                            putExtra(Intent.EXTRA_TEXT, textContent!!)
                        }, "Share transcript"))
                    }
                } else null,
                onSetCheckpoint = vm::setLearningCheckpoint,
                // This is an explicit reader action, so it must work mid-chapter;
                // the chapter-completion prompt is reserved for automatic reminders.
                onExplainBack = {
                    val chIdx = currentChapterIndex ?: book?.currentChapterIndex
                    if (chIdx != null) {
                        val chTitle = chapterLabel ?: "Chapter ${chIdx + 1}"
                        onExplainBack(
                            bookId,
                            chIdx,
                            chTitle,
                            book?.title ?: "Book",
                            currentLocator?.toJSON()?.toString(),
                            vm.currentLearningPosition().second
                        )
                    }
                },
                isTextBook = book?.format == "txt" && textContent != null,
                enhancing = enhancing,
                onEnhance = vm::enhanceWithAI,
                enhancementProgress = enhancementProgress,
                lumenCapturing = lumenCapturing,
                onCaptureLumen = vm::captureLumenCard,
                onOpenLumen = { onOpenLumenCards(bookId) }
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.End) {
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

    
    }

    conceptMap.concepts.firstOrNull { it.id == activeConceptId }?.let { concept ->
        EpubConceptSheet(
            concept = concept,
            relationships = conceptMap.relationships,
            concepts = conceptMap.concepts,
            onOpenMap = {
                activeConceptId = null
                onOpenConcepts(bookId)
            },
            onDismiss = { activeConceptId = null }
        )
    }

    if (showChapterReviewPrompt) {
        ChapterReviewPrompt(
            chapterLabel = chapterLabel ?: "the current chapter",
            onExplain = {
                showChapterReviewPrompt = false
                // Explain the chapter currently visible in the navigator. Never
                // silently fall back to chapter zero: on a fresh EPUB the locator
                // may not have arrived yet, so ask the reader to retry instead of
                // opening the wrong chapter.
                val chIdx = currentChapterIndex ?: book?.currentChapterIndex
                if (chIdx != null) {
                    val chTitle = chapterLabel ?: "Chapter ${chIdx + 1}"
                    val bTitle = book?.title ?: "Book"
                    onExplainBack(
                        bookId,
                        chIdx,
                        chTitle,
                        bTitle,
                        currentLocator?.toJSON()?.toString(),
                        null
                    )
                }
            },
            onDismiss = { showChapterReviewPrompt = false }
        )
    }

    if (showSettings) {
        ReaderAppearanceSheet(
            settings = settings,
            onApply = vm::applyReaderSettings,
            onDismiss = { showSettings = false }
        )
    }

    lumenDraft?.let { draft ->
        LumenDraftDialog(
            draft = draft,
            suggestions = lumenFileSuggestions,
            boxCards = vm.lumenBoxCards.collectAsStateWithLifecycle().value,
            captureDiagnostic = vm.captureDiagnostic.collectAsStateWithLifecycle().value,
            captureLog = vm.lastCaptureLog(),
            redrafting = lumenCapturing,
            onRetry = vm::retryLumenCard,
            onSave = { front, back, afterIndex -> vm.saveLumenCard(front, back, afterIndex) },
            onDismiss = vm::dismissLumenDraft
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
    concepts: List<com.pagetime.app.data.local.ConceptEntity>,
    conceptLevel: String,
    activeConceptId: String?,
    goRequest: Pair<Float, Long>?,
    onPageChanged: (page: Int, pageCount: Int, pageStartOffset: Int, userInitiated: Boolean) -> Unit,
    onRestoreComplete: () -> Unit,
    onToggleChrome: () -> Unit
) {
    // Pages are laid out from the device's REAL screen size and the reader's
    // exact typography: a page is whatever measured text actually fits the
    // visible area on this phone. Nothing is counted or guessed, so changing
    // the font, the device, or the system text scale re-flows the full text
    // with nothing clipped and no missing pages.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp
    val textMeasurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.bodyLarge

    var pagesState by remember { mutableStateOf<List<TextPage>?>(null) }
    var layoutProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(
        content,
        settings.fontSizeSp,
        settings.lineHeight,
        settings.fontFamily,
        settings.marginDp,
        settings.alignment,
        screenW,
        screenH,
        density
    ) {
        val widthPx = with(density) {
            (screenW - (settings.marginDp.dp * 2)).coerceAtLeast(48.dp).roundToPx()
        }
        val heightPx = with(density) {
            (screenH - 48.dp).coerceAtLeast(96.dp).roundToPx()
        }
        val family = readerFontFamily(settings.fontFamily)
        val renderStyle = baseStyle.copy(
            letterSpacing = (settings.fontSizeSp * 0.015f).sp,
            fontFamily = family,
            fontSize = settings.fontSizeSp.sp,
            lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
            textAlign = if (settings.alignment == "justify") TextAlign.Justify else TextAlign.Start
        )
        // Cap the binary-search measurement window to a generous multiple of
        // what a page can hold. Without it the search measures the ENTIRE
        // remaining book per page — O(book²) layout work that froze the UI for
        // seconds on long transcripts. 3× a page's capacity keeps pages just
        // as full while making layout linear in book size.
        val windowChars = with(density) {
            val avgCharPx = textMeasurer.measure(
                text = "mmmmmmmmmm",
                style = renderStyle,
                constraints = Constraints(maxWidth = widthPx)
            ).size.width / 10f
            val charsPerLine = (widthPx / avgCharPx).toInt().coerceAtLeast(8)
            val linesPerPage = (heightPx / (settings.fontSizeSp * settings.lineHeight * density.fontScale).dp
                .roundToPx()).coerceAtLeast(1)
            (charsPerLine * linesPerPage * 3).coerceIn(2_000, 200_000)
        }
        layoutProgress = 0f
        pagesState = runCatching {
            TextPageLayout.paginateMeasured(
                content = content,
                maxHeightPx = heightPx,
                measureHeightPx = { snippet ->
                    textMeasurer.measure(
                        text = snippet,
                        style = renderStyle,
                        constraints = Constraints(maxWidth = widthPx)
                    ).size.height
                },
                maxWindowChars = windowChars
            ) {
                layoutProgress = it
                // Stay responsive: hand control back to the UI between pages.
                yield()
            }
        }.getOrElse {
            // Measurement should never fail; fall back to the heuristic layout
            // so the reader always shows the full book rather than nothing.
            TextPageLayout.paginate(content, TextPageLayout.targetCharsFor(settings))
        }
    }

    val pages = pagesState
    if (pages == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(palette.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = palette.text)
                if (layoutProgress > 0f && layoutProgress < 1f) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Laying out pages… ${(layoutProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.text
                    )
                }
            }
        }
        return
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
            val annotatedText = rememberAnnotatedPage(
                pageText = pages[pageIndex].text,
                concepts = concepts,
                level = conceptLevel,
                activeConceptId = activeConceptId
            )
            Text(
                text = annotatedText,
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

    // initialLocatorJson is deliberately NOT a key: the ViewModel now refreshes it
    // on every position save, and re-keying would tear down and recreate the live
    // navigator each time. The effect restarts on re-entry anyway (container is a
    // fresh FrameLayout) and reads the then-current restore locator, so returning
    // to the reader resumes where the user actually was instead of a stale
    // session-open position.
    LaunchedEffect(fragmentManager, container, publication, initialLocatorReady) {
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
        // Readium can only use fonts available to the EPUB WebView. Literata is
        // bundled for plain-text books, so EPUBs use the matching serif family.
        else -> ReadiumFontFamily.SERIF
    },
    theme = when (s.theme) {
        "dark", "night" -> Theme.DARK
        "sepia" -> Theme.SEPIA
        else -> Theme.LIGHT
    },
    // publisherStyles must be disabled for user alignment, line spacing, and
    // margins to take effect in Readium.
    publisherStyles = false,
    textAlign = when (s.alignment) {
        "justify" -> ReadiumTextAlign.JUSTIFY
        else -> ReadiumTextAlign.START
    },
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
    checkpointPresent: Boolean,
    sleepRemainingSeconds: Int?,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onGoTo: () -> Unit,
    onStats: () -> Unit,
    onSleepTimer: () -> Unit,
    onBookmark: () -> Unit,
    onSettings: () -> Unit,
    onCopyTranscript: (() -> Unit)? = null,
    onShareTranscript: (() -> Unit)? = null,
    onSetCheckpoint: () -> Unit = {},
    onExplainBack: () -> Unit,
    isTextBook: Boolean = false,
    enhancing: Boolean = false,
    onEnhance: () -> Unit = {},
    enhancementProgress: Pair<Int, Int>? = null,
    lumenCapturing: Boolean = false,
    onCaptureLumen: () -> Unit = {},
    onOpenLumen: () -> Unit = {}
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
                        text = { Text(if (checkpointPresent) "Update learning checkpoint" else "Set learning checkpoint") },
                        leadingIcon = { Icon(Icons.Outlined.School, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onSetCheckpoint()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Explain what you learned") },
                        leadingIcon = { Icon(Icons.Outlined.School, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onExplainBack()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            if (lumenCapturing) {
                                Text("Capturing…")
                            } else {
                                Text("New Lumen card")
                            }
                        },
                        leadingIcon = {
                            if (lumenCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.NoteAdd, contentDescription = null)
                            }
                        },
                        onClick = {
                            optionsExpanded = false
                            onCaptureLumen()
                        },
                        enabled = !lumenCapturing
                    )
                    DropdownMenuItem(
                        text = { Text("View Lumen cards") },
                        leadingIcon = { Icon(Icons.Outlined.Style, contentDescription = null) },
                        onClick = {
                            optionsExpanded = false
                            onOpenLumen()
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
                    if (onCopyTranscript != null) {
                        DropdownMenuItem(
                            text = { Text("Copy transcript") },
                            onClick = { optionsExpanded = false; onCopyTranscript() }
                        )
                    }
                    if (onShareTranscript != null) {
                        DropdownMenuItem(
                            text = { Text("Share transcript") },
                            onClick = { optionsExpanded = false; onShareTranscript() }
                        )
                    }
                    if (isTextBook) {
                        DropdownMenuItem(
                            text = {
                                if (enhancing) {
                                    val progress = enhancementProgress
                                    Text(
                                        if (progress != null) {
                                            "Formatting ${progress.first}/${progress.second}"
                                        } else {
                                            "Preparing…"
                                        }
                                    )
                                } else Text("\u2728 Enhance with AI")
                            },
                            leadingIcon = {
                                if (enhancing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                }
                            },
                            onClick = {
                                optionsExpanded = false
                                onEnhance()
                            },
                            enabled = !enhancing
                        )
                    }
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

/** Small section label inside the File-behind menu. */
@Composable
private fun MenuHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LumenDraftDialog(
    draft: LumenDraft,
    suggestions: List<LumenCardEntity>,
    boxCards: List<LumenCardEntity>,
    captureDiagnostic: com.pagetime.app.data.CaptureDiagnostic.Record?,
    captureLog: List<String>,
    redrafting: Boolean,
    onRetry: () -> Unit,
    onSave: (front: String, back: String, afterIndex: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var front by remember(draft) { mutableStateOf(draft.front) }
    var back by remember(draft) { mutableStateOf(draft.back) }
    var fileBehind by remember(draft) { mutableStateOf<LumenCardEntity?>(null) }
    var filingMenu by remember(draft) { mutableStateOf(false) }
    var filingSearch by remember(draft) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.NoteAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("New Lumen card")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Three cases worth saying out loud: no AI at all, AI that
                // produced nothing usable, and an AI card that needs the
                // reader's eye — a thin note, or the same idea as a card they
                // already have. Only the first has nothing to re-ask.
                if (!draft.usedAi || draft.aiShortfall != null) {
                    Text(
                        when {
                            draft.aiShortfall == null ->
                                "Drafted on-device (no AI key or offline) — edit freely."
                            !draft.usedAi ->
                                "The offline model didn't land a card — ${draft.aiShortfall}. " +
                                    "This draft is straight from the passage; edit it, or ask again."
                            else ->
                                "Check this one — ${draft.aiShortfall}. Edit it, or ask again."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (draft.aiShortfall != null) {
                        TextButton(
                            onClick = onRetry,
                            enabled = !redrafting,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            if (redrafting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Asking again…")
                            } else {
                                Text("Try again")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Title / question") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text("Your note (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (captureDiagnostic != null) {
                    Text(
                        when (captureDiagnostic.modelState) {
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.ready -> "Offline model: ready"
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.notInstalled -> "Offline model: not installed"
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.damaged -> "Offline model: damaged"
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.notEnoughMemory -> "Offline model: not enough memory"
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.generating -> "Offline model: generating..."
                            com.pagetime.app.data.CaptureDiagnostic.ModelState.fallbackNoModel -> "Offline model: not used"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        draft.quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "FILE BEHIND",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                                    if (captureLog.isNotEmpty()) {
                        CopyCaptureLogButton(captureLog)
                        Spacer(Modifier.height(8.dp))
                    }
                    Box {
                        TextButton(
                            onClick = { filingMenu = true },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (fileBehind == null) {
                                Text(
                                    "End of box",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                FilingSelectionLabel(
                                    modifier = Modifier.weight(1f),
                                    card = fileBehind!!,
                                    boxCards = boxCards
                                )
                            }
                            Icon(
                                Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = filingMenu,
                            onDismissRequest = { filingMenu = false },
                            modifier = Modifier.heightIn(max = 520.dp)
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                DropdownMenuItem(
                                    text = { Text("End of box") },
                                    onClick = {
                                        fileBehind = null
                                        filingMenu = false
                                    }
                                )
                                if (suggestions.isNotEmpty()) {
                                    HorizontalDivider()
                                    MenuHeader("SUGGESTED FOR THIS IDEA")
                                    suggestions.forEach { card ->
                                        DropdownMenuItem(
                                            text = { FilingItem(card = card, boxCards = boxCards) },
                                            onClick = {
                                                fileBehind = card
                                                filingMenu = false
                                            }
                                        )
                                    }
                                }
                                val inBox = boxCards.filter { it.box == 1 }
                                if (LumenAddress.shelfOrder(inBox).isNotEmpty()) {
                                    HorizontalDivider()
                                    MenuHeader("BROWSE ALL LINES")
                                    OutlinedTextField(
                                        value = filingSearch,
                                        onValueChange = { filingSearch = it },
                                        placeholder = { Text("Search address or title…") },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                    val query = filingSearch.trim().lowercase()
                                    val browse = LumenAddress.shelfOrder(inBox).filter { card ->
                                        query.isEmpty() ||
                                            card.indexNumber.lowercase().contains(query) ||
                                            card.front.lowercase().contains(query)
                                    }
                                    browse.forEach { card ->
                                        DropdownMenuItem(
                                            text = { FilingItem(card = card, boxCards = boxCards) },
                                            onClick = {
                                                fileBehind = card
                                                filingMenu = false
                                            }
                                        )
                                    }
                                    if (browse.isEmpty()) {
                                        Text(
                                            "No note in the box matches that search.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(front, back, fileBehind?.indexNumber) },
                enabled = front.isNotBlank()
            ) {
                Text("Save card")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Discard") }
        }
    )
}

/**
 * The selected filing target inside the picker: the card's address threaded
 * through its branch (21 → 21a → 21a1) and its front. Reads like branching a
 * Luhmann line, not choosing a bare address.
 */
@Composable
private fun FilingSelectionLabel(
    modifier: Modifier = Modifier,
    card: LumenCardEntity,
    boxCards: List<LumenCardEntity>
) {
    Column(modifier) {
        FilingTargetText(card = card, boxCards = boxCards)
    }
}

/**
 * One candidate in the File-behind menu: the front line, with the address
 * branch it belongs to shown underneath so the user sees which line they'd
 * continue.
 */
@Composable
private fun FilingItem(card: LumenCardEntity, boxCards: List<LumenCardEntity>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.front,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                card.indexNumber,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(2.dp))
        ThreadPathText(address = card.indexNumber, boxCards = boxCards)
    }
}

/**
 * Renders the target label inside the picker button: the address line and the
 * front, both clipped to a single line.
 */
@Composable
private fun FilingTargetText(card: LumenCardEntity, boxCards: List<LumenCardEntity>) {
    val path = LumenAddress.threadPath(card.indexNumber, boxCards)
    Column {
        Text(
            path.map { it.first }.joinToString(" → "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            card.front,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Muted "in {branch}" breadcrumb under a candidate's front in the menu. */
@Composable
private fun ThreadPathText(address: String, boxCards: List<LumenCardEntity>) {
    val ancestors = LumenAddress.threadPath(address, boxCards).dropLast(1)
    Text(
        text = if (ancestors.isEmpty()) {
            "new main line — ${LumenAddress.relativePart(address)}"
        } else {
            buildString {
                append("continues ")
                append(ancestors.joinToString(" → ") { "${it.first}: ${it.second}" })
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CopyCaptureLogButton(captureLog: List<String>) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    Button(
        onClick = {
            try {
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("PageTime capture log", captureLog.joinToString("\n"))
                )
            } catch (t: Throwable) {
                // Clipboard access failed; no-op.
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Outlined.CopyAll,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("Copy capture log")
    }
}
