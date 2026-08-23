package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.os.Bundle
import android.widget.FrameLayout
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.School
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModelFactory(app, bookId))

    val book by vm.book.collectAsStateWithLifecycle()
    val textContent by vm.textContent.collectAsStateWithLifecycle()
    val initialTextFraction by vm.initialTextFraction.collectAsStateWithLifecycle()
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

    val palette = paletteFor(settings.theme)
    val scrollState = rememberScrollState()

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showCardSheet by remember { mutableStateOf(false) }
    var showChapterPrompt by remember { mutableStateOf(false) }
    var lastPromptedChapter by remember { mutableStateOf<Int?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var chapterLabel by remember { mutableStateOf<String?>(null) }
    var epubRestoreFinished by remember { mutableStateOf(false) }

    val tocEntries = remember(publication) {
        publication?.tableOfContents?.takeIf { it.isNotEmpty() }?.let { flattenToc(it) }
    }

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
                vm.persistTextPositionNow(scrollState.value.toFloat() / scrollState.maxValue)
            }
        }
    }

    // Restore and observe plain-text scrolling in one ordered coroutine. The first
    // scrollState emission is normally 0 while Compose is laying out; observing it
    // in a separate effect could save that startup value over the real position.
    LaunchedEffect(textContent, book?.id, initialTextFraction) {
        if (textContent == null || book?.format != "txt") return@LaunchedEffect

        val targetFraction = initialTextFraction.coerceIn(0f, 1f)
        var restoreApplied = targetFraction <= 0f
        var attempts = 0
        while (!restoreApplied && attempts < 40) { // up to ~2s for the scroll range
            val max = scrollState.maxValue
            if (max > 0) {
                scrollState.scrollTo((targetFraction * max).toInt())
                restoreApplied = true
            } else {
                delay(50)
                attempts++
            }
        }

        // A zero scroll range means the whole text fits on one screen, so there is
        // no position to restore. For a real scrollable book, never persist until
        // the saved fraction was actually applied.
        if (!restoreApplied && scrollState.maxValue > 0) return@LaunchedEffect
        vm.markTxtRestoreComplete()

        snapshotFlow {
            Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress)
        }.collect { (value, max, userIsScrolling) ->
            // maxValue can change during layout and scrollTo() emits too. Neither
            // represents user reading movement and neither may overwrite the saved
            // position or mint reading credit.
            if (!userIsScrolling) return@collect
            vm.onUserScrolled()
            val fraction = if (max > 0) value.toFloat() / max else 0f
            vm.onProgressChanged(fraction)
            vm.updateScrollProgress(fraction)
            vm.maybeGenerateCardsForTextProgress(fraction)
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

            book?.format == "txt" && textContent != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
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

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            ReaderTopBar(
                title = book?.title ?: "Reading",
                hasChapters = tocEntries != null,
                balanceSeconds = balanceSeconds,
                bookmarkPresent = bookmarkPresent,
                palette = palette,
                onBack = onBack,
                onToc = { showToc = true },
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
                    chapterLabel = chapterLabel
                )
            }
        }

        if (guardState.showIdleGate) {
            IdleGate(onContinue = { vm.resumeAfterIdle() })
        }

        if (resumeNotice != null) {
            ResumeNotice(text = resumeNotice!!)
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
        ReaderSettingsSheet(
            settings = settings,
            onApply = vm::applyReaderSettings,
            onDismiss = { showSettings = false }
        )
    }

    if (showToc && tocEntries != null) {
        TocSheet(
            entries = tocEntries,
            onSelect = { entry ->
                navigator?.go(entry.link)
                showToc = false
            },
            onDismiss = { showToc = false }
        )
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
    fontSize = (s.fontSizeSp / 16.0) * 100.0,
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
    balanceSeconds: Long,
    bookmarkPresent: Boolean,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onCreateCard: () -> Unit,
    onSettings: () -> Unit
) {
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
            if (hasChapters) {
                IconButton(onClick = onToc) {
                    Icon(Icons.Filled.List, contentDescription = "Chapters")
                }
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarkPresent) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (bookmarkPresent) "Remove bookmark" else "Bookmark this position"
                )
            }
            IconButton(onClick = onCreateCard) {
                Icon(Icons.Outlined.School, contentDescription = "Create review card")
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

private fun txtFontFamily(key: String): FontFamily = when (key) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    "literata" -> FontFamily(Font(R.font.literata)) // Google's book-reading typeface, OFL-licensed
    else -> FontFamily.Serif
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onApply: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var fontSize by remember(settings) { mutableStateOf(settings.fontSizeSp) }
    var lineHeight by remember(settings) { mutableStateOf(settings.lineHeight) }
    var fontFamily by remember(settings) { mutableStateOf(settings.fontFamily) }
    var theme by remember(settings) { mutableStateOf(settings.theme) }
    var margin by remember(settings) { mutableStateOf(settings.marginDp) }

    fun applyAndDismiss() {
        onApply(
            ReaderSettings(
                fontSizeSp = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                theme = theme,
                marginDp = margin
            )
        )
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::applyAndDismiss) {
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

            Text("Text size — ${fontSize.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 12f..32f)

            Text("Line spacing — ${
                "%.1f".format(lineHeight)
            }", style = MaterialTheme.typography.bodyMedium)
            Slider(value = lineHeight, onValueChange = { lineHeight = it }, valueRange = 1f..2.2f)

            Text("Margins — ${margin.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = margin, onValueChange = { margin = it }, valueRange = 8f..48f)

            Text("Font", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "serif" to "Serif",
                    "sans" to "Sans",
                    "mono" to "Mono",
                    "literata" to "Literata"
                ).forEach { (key, label) ->
                    SelectorPill(selected = fontFamily == key, label = label) { fontFamily = key }
                }
            }

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderPalettes.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ThemeSwatch(selected = theme == p.key, palette = p) { theme = p.key }
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
    entries: List<TocEntry>,
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
