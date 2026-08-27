package com.pagetime.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pagetime.app.ui.screens.library.LibraryScreen
import com.pagetime.app.ui.screens.reader.ReaderScreen
import com.pagetime.app.ui.screens.review.ReviewScreen
import com.pagetime.app.ui.screens.discover.DiscoverScreen
import com.pagetime.app.ui.screens.concepts.ConceptMapScreen
import com.pagetime.app.ui.screens.settings.BlockedAppsScreen
import com.pagetime.app.ui.screens.settings.PermissionsScreen
import com.pagetime.app.ui.screens.settings.SettingsScreen
import com.pagetime.app.ui.screens.settings.UsageAuditScreen
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
    BottomTab("review", "Review", Icons.Outlined.School, Icons.Filled.School),
    BottomTab("concepts", "Map", Icons.Outlined.AccountTree, Icons.Filled.AccountTree),
    BottomTab("search", "Discover", Icons.Outlined.Search, Icons.Filled.Search),
    BottomTab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun PageTimeAppUi(openReader: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val learningBadgeViewModel: LearningBadgeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dueCount by learningBadgeViewModel.dueCount.collectAsStateWithLifecycle()
    val showBottomBar = currentRoute in setOf("library", "review", "concepts", "search", "settings")

    LaunchedEffect(openReader) {
        if (openReader) {
            navController.navigate("reader/last") { launchSingleTop = true }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                                if (tab.route == "review" && dueCount > 0) {
                                    BadgedBox(
                                        badge = { Badge { Text(dueCount.coerceAtMost(99).toString()) } }
                                    ) {
                                        Icon(
                                            if (selected) tab.filled else tab.outlined,
                                            contentDescription = tab.label
                                        )
                                    }
                                } else {
                                    Icon(
                                        if (selected) tab.filled else tab.outlined,
                                        contentDescription = tab.label
                                    )
                                }
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
                    onOpenConcepts = { bookId -> navController.navigate("concepts?bookId=$bookId") },
                    onDiscover = { navController.navigate("search") }
                )
            }
            composable("review") {
                ReviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSource = { bookId -> navController.navigate("reader/$bookId") }
                )
            }
            composable("concepts") {
                ConceptMapScreen(onBack = { navController.popBackStack() })
            }
            composable("concepts?bookId={bookId}") { entry ->
                ConceptMapScreen(
                    onBack = { navController.popBackStack() },
                    initialBookId = entry.arguments?.getString("bookId")
                )
            }
            composable("search") { DiscoverScreen() }
            composable("settings") {
                SettingsScreen(
                    onManageBlockedApps = { navController.navigate("blocked_apps") },
                    onPermissions = { navController.navigate("permissions") },
                    onUsageAudit = { navController.navigate("usage_audit") },
                    onAiUsage = { navController.navigate("ai_usage") }
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
            composable("ai_usage") {
                AiUsageScreen(onBack = { navController.popBackStack() })
            }
            composable("reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: "last"
                ReaderScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenConcepts = { conceptBookId -> navController.navigate("concepts?bookId=$conceptBookId") },
                    onExplainBack = { bookId, chapterIndex, chapterTitle, bookTitle ->
                        val encodedTitle = URLEncoder.encode(chapterTitle, "UTF-8")
                        val encodedBookTitle = URLEncoder.encode(bookTitle, "UTF-8")
                        navController.navigate(
                            "explain-back/$bookId/$chapterIndex/$encodedTitle/$encodedBookTitle"
                        )
                    }
                )
            }
            composable("explain-back/{bookId}/{chapterIndex}/{chapterTitle}/{bookTitle}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: ""
                val chapterIndex = entry.arguments?.getString("chapterIndex")?.toIntOrNull() ?: 0
                val chapterTitle = URLDecoder.decode(
                    entry.arguments?.getString("chapterTitle") ?: "", "UTF-8"
                )
                val bookTitle = URLDecoder.decode(
                    entry.arguments?.getString("bookTitle") ?: "", "UTF-8"
                )
                val context = androidx.compose.ui.platform.LocalContext.current
                val app = context.applicationContext as com.pagetime.app.PageTimeApp
                val vm: ExplainBackViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = ExplainBackViewModelFactory(app, bookId, chapterIndex, bookTitle, chapterTitle)
                )
                val concepts by vm.concepts.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val isLoading by vm.isLoading.collectAsStateWithLifecycle()
                val conceptsLoading by vm.conceptsLoading.collectAsStateWithLifecycle()
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
                        onBack = { navController.popBackStack() }
                    )
                } else {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.Text(
                            "No learning concepts are available for this chapter yet.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        androidx.compose.material3.TextButton(onClick = { navController.popBackStack() }) {
                            androidx.compose.material3.Text("Back to reading")
                        }
                    }
                }
            }
        }
    }
}