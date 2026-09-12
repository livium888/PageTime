package com.pagetime.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pagetime.app.BookImportViewModel
import com.pagetime.app.ui.screens.library.LibraryScreen
import com.pagetime.app.ui.screens.bookshelf.BookshelfScreen
import com.pagetime.app.ui.screens.shelf.AuthorShelfScreen
import com.pagetime.app.ui.screens.shelf.ShelfScreen
import com.pagetime.app.ui.screens.reader.ReaderScreen
import com.pagetime.app.ui.screens.reader.PdfReaderScreen
import com.pagetime.app.ui.screens.discover.DiscoverScreen
import com.pagetime.app.ui.screens.concepts.ConceptMapScreen
import com.pagetime.app.ui.screens.flashcards.FlashcardsScreen
import com.pagetime.app.ui.screens.highlights.HighlightsScreen
import com.pagetime.app.ui.screens.lumen.LumenCardsScreen
import com.pagetime.app.ui.screens.pagemarks.PagemarkQueueScreen
import com.pagetime.app.ui.screens.review.ReviewSessionScreen
import com.pagetime.app.ui.screens.settings.BlockedAppsScreen
import com.pagetime.app.ui.screens.settings.PermissionsScreen
import com.pagetime.app.ui.screens.settings.SettingsScreen
import com.pagetime.app.ui.screens.settings.UsageAuditScreen
import com.pagetime.app.ui.screens.settings.AiModelsScreen
import com.pagetime.app.ui.screens.settings.AiUsageScreen
import com.pagetime.app.ui.screens.reader.ExplainBackScreen
import com.pagetime.app.ui.screens.reader.ExplainBackViewModel
import com.pagetime.app.ui.screens.reader.ExplainBackViewModelFactory
import java.net.URLDecoder
import java.net.URLEncoder

private data class BottomTab(
    val route: String,
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector
)

private val tabs = listOf(
    BottomTab("library", "Library", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
    BottomTab("lumen", "Lumen", Icons.Outlined.Style, Icons.Filled.Style),
    // Distinct from Lumen on purpose. A Lumen card is a note the reader wrote
    // to think with; a flashcard is a question generated from a passage to be
    // answered from memory. Conflating them has confused a reader already.
    BottomTab("flashcards", "Recall", Icons.Outlined.Quiz, Icons.Filled.Quiz),
    BottomTab("search", "Discover", Icons.Outlined.Search, Icons.Filled.Search),
    BottomTab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun PageTimeAppUi(
    openReader: Boolean,
    /** Which book a blocked-app bounce should open; null = the last book. */
    openReaderBookId: String? = null,
    /** The launch came from a review reminder; go straight to the sitting. */
    openReview: Boolean = false,
    onReviewOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar =
        currentRoute in setOf("library", "lumen", "flashcards", "search", "settings")
    val importViewModel: BookImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openReader, openReaderBookId) {
        if (openReader) {
            navController.navigate("reader/${openReaderBookId ?: "last"}") { launchSingleTop = true }
        }
    }

    // Straight to the review, and the flag is consumed so a rotation does not
    // fling the reader back here after they have navigated away.
    LaunchedEffect(openReview) {
        if (openReview) {
            navController.navigate("review") { launchSingleTop = true }
            onReviewOpened()
        }
    }

    // A book opened or shared from outside the app is imported by the shared
    // BookImportViewModel and then opened in the reader; failures are surfaced
    // with the same snackbar used for picker import errors.
    LaunchedEffect(importState) {
        when (val result = importState) {
            BookImportViewModel.State.Idle -> Unit
            BookImportViewModel.State.Importing ->
                snackbarHostState.showSnackbar("Importing book…", duration = SnackbarDuration.Indefinite)
            is BookImportViewModel.State.Done -> {
                navController.navigate("reader/${result.book.id}") { launchSingleTop = true }
                importViewModel.consume()
            }
            is BookImportViewModel.State.Failed -> {
                snackbarHostState.showSnackbar(result.message)
                importViewModel.consume()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) tab.filled else tab.outlined,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("library") {
                LibraryScreen(
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                    onOpenPdf = { bookId -> navController.navigate("pdf-reader/$bookId") },
                    onOpenConcepts = { bookId -> navController.navigate("concepts/$bookId") },
                    onDiscover = { navController.navigate("search") },
                    onOpenShelf = { navController.navigate("shelf") },
                    onOpenBookshelf = { navController.navigate("bookshelf") },
                    onOpenPagemarks = { navController.navigate("pagemarks") },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${URLEncoder.encode(author, "UTF-8")}")
                    }
                )
            }
            composable("bookshelf") {
                BookshelfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                    // A book on the shelf you do not have still has to do
                    // something when tapped, and where books come from is the
                    // author's shelf.
                    onOpenAuthor = { author ->
                        navController.navigate("author/${URLEncoder.encode(author, "UTF-8")}")
                    }
                )
            }
            composable("shelf") {
                ShelfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") }
                )
            }
            composable("pagemarks") {
                PagemarkQueueScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId ->
                        navController.navigate("reader/$bookId") { launchSingleTop = true }
                    }
                )
            }
            composable("author/{name}") { entry ->
                AuthorShelfScreen(
                    authorName = URLDecoder.decode(
                        entry.arguments?.getString("name") ?: "", "UTF-8"
                    ),
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") }
                )
            }
            // The concept map screen was written, and then never given a route:
            // every "open concepts" callback navigated to the Lumen box instead,
            // while the maps themselves went on being generated — and paid for
            // in Gemini calls — with no way to look at them.
            composable("concepts/{bookId}") { entry ->
                ConceptMapScreen(
                    onBack = { navController.popBackStack() },
                    initialBookId = entry.arguments?.getString("bookId")?.takeIf { it.isNotBlank() }
                )
            }
            composable("lumen") {
                LumenCardsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSource = { bookId -> navController.navigate("reader/$bookId") },
                    onOpenReview = { navController.navigate("review") }
                )
            }
            // The same failure as the concept map above, one layer deeper: the
            // scheduler, the ratings and the due query were all built, and the
            // only thing that could reach them was a chip that selected a box.
            composable("review") {
                ReviewSessionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSource = { bookId -> navController.navigate("reader/$bookId") },
                    onReadChunk = { bookId ->
                        navController.navigate("reader/$bookId") { launchSingleTop = true }
                    }
                )
            }
            composable("flashcards") {
                FlashcardsScreen(
                    onOpenReview = { navController.navigate("review") },
                    onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                )
            }
            composable("search") { DiscoverScreen() }
            composable("settings") {
                SettingsScreen(
                    onManageBlockedApps = { navController.navigate("blocked_apps") },
                    onPermissions = { navController.navigate("permissions") },
                    onUsageAudit = { navController.navigate("usage_audit") },
                    onAiUsage = { navController.navigate("ai_usage") },
                    onAiModels = { navController.navigate("ai_models") }
                )
            }
            composable("blocked_apps") { BlockedAppsScreen(onBack = { navController.popBackStack() }) }
            composable("permissions") { PermissionsScreen(onBack = { navController.popBackStack() }) }
            composable("usage_audit") {
                UsageAuditScreen(
                    onBack = { navController.popBackStack() },
                    onPermissions = { navController.navigate("permissions") }
                )
            }
            composable("ai_models") {
                AiModelsScreen(
                    onBack = { navController.popBackStack() },
                    onAiUsage = { navController.navigate("ai_usage") }
                )
            }
            composable("ai_usage") {
                AiUsageScreen(onBack = { navController.popBackStack() })
            }
            // The highlights list is reached from the reader's Options menu, so
            // it sits beside the reader rather than under a bottom tab: a
            // reader wanting their marks is in the book, not in the library.
            composable("highlights/{bookId}") { entry ->
                HighlightsScreen(
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenBook = { id ->
                        navController.navigate("reader/$id") { launchSingleTop = true }
                    }
                )
            }
            composable("pdf-reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                PdfReaderScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: "last"
                ReaderScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenHighlights = { highlightBookId ->
                        navController.navigate("highlights/$highlightBookId")
                    },
                    onOpenConcepts = { conceptBookId -> navController.navigate("concepts/$conceptBookId") },
                    onOpenLumenCards = { navController.navigate("lumen") },
                    onExplainBack = { bookId, chapterIndex, chapterTitle, bookTitle, locatorJson, textOffset ->
                        val encodedTitle = URLEncoder.encode(chapterTitle, "UTF-8")
                        val encodedBookTitle = URLEncoder.encode(bookTitle, "UTF-8")
                        val encodedLocator = URLEncoder.encode(locatorJson.orEmpty(), "UTF-8")
                        navController.navigate(
                            "explain-back/$bookId/$chapterIndex/$encodedTitle/$encodedBookTitle?locator=$encodedLocator&offset=${textOffset ?: -1}"
                        )
                    }
                )
            }
            composable("explain-back/{bookId}/{chapterIndex}/{chapterTitle}/{bookTitle}?locator={locator}&offset={offset}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: ""
                val chapterIndex = entry.arguments?.getString("chapterIndex")?.toIntOrNull() ?: 0
                val chapterTitle = URLDecoder.decode(
                    entry.arguments?.getString("chapterTitle") ?: "", "UTF-8"
                )
                val bookTitle = URLDecoder.decode(
                    entry.arguments?.getString("bookTitle") ?: "", "UTF-8"
                )
                val locatorJson = entry.arguments?.getString("locator")
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                    ?.takeIf { it.isNotBlank() }
                val textOffset = entry.arguments?.getString("offset")?.toIntOrNull()?.takeIf { it >= 0 }
                val context = androidx.compose.ui.platform.LocalContext.current
                val app = context.applicationContext as com.pagetime.app.PageTimeApp
                val vm: ExplainBackViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = ExplainBackViewModelFactory(
                        app,
                        bookId,
                        chapterIndex,
                        bookTitle,
                        chapterTitle,
                        locatorJson,
                        textOffset
                    )
                )
                val concepts by vm.concepts.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val isLoading by vm.isLoading.collectAsStateWithLifecycle()
                val conceptsLoading by vm.conceptsLoading.collectAsStateWithLifecycle()
                val needsConceptGeneration by vm.needsConceptGeneration.collectAsStateWithLifecycle()
                val isFinished by vm.isFinished.collectAsStateWithLifecycle()
                val explainError by vm.error.collectAsStateWithLifecycle()
                val explanationHistory by vm.explanationHistory.collectAsStateWithLifecycle()
                val awaitingRestatement by vm.awaitingRestatement.collectAsStateWithLifecycle()
                val requestsUsed by vm.requestsUsed.collectAsStateWithLifecycle()

                if (isFinished) {
                    navController.popBackStack()
                } else if (conceptsLoading) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else if (explainError != null) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.Text(
                            explainError ?: "Could not load this chapter",
                            color = MaterialTheme.colorScheme.error
                        )
                        androidx.compose.material3.TextButton(onClick = vm::retryConcepts) {
                            androidx.compose.material3.Text("Try again")
                        }
                    }
                } else if (needsConceptGeneration) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.Text(
                            "No saved concept covers this reading range yet.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        androidx.compose.material3.TextButton(onClick = vm::createLearningConcept) {
                            androidx.compose.material3.Text("Create learning concept")
                        }
                        androidx.compose.material3.TextButton(onClick = { navController.popBackStack() }) {
                            androidx.compose.material3.Text("Back to reading")
                        }
                    }
                } else if (concepts.isNotEmpty()) {
                    ExplainBackScreen(
                        conceptLabel = vm.currentConcept,
                        bookTitle = bookTitle,
                        chapterTitle = chapterTitle,
                        messages = messages,
                        isLoading = isLoading,
                        awaitingRestatement = awaitingRestatement,
                        requestsUsed = requestsUsed,
                        canRevise = messages.any { it.isAi },
                        onSendExplanation = vm::submitExplanation,
                        onRevise = vm::revise,
                        onNextConcept = vm::nextConcept,
                        history = explanationHistory,
                        onDeleteHistory = vm::deleteHistory,
                        onBack = { navController.popBackStack() },
                        onCreateConcept = vm::createLearningConcept
                    )
                } else {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.Text("No learning concept is available yet.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        androidx.compose.material3.TextButton(onClick = vm::createLearningConcept) {
                            androidx.compose.material3.Text("Create learning concept")
                        }
                    }
                }
            }
        }
    }
}