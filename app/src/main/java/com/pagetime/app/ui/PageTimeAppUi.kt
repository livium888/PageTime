package com.pagetime.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
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
import com.pagetime.app.ui.screens.settings.BlockedAppsScreen
import com.pagetime.app.ui.screens.settings.PermissionsScreen
import com.pagetime.app.ui.screens.settings.SettingsScreen
import com.pagetime.app.ui.screens.settings.UsageAuditScreen

private data class BottomTab(
    val route: String,
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector
)

private val tabs = listOf(
    BottomTab("library", "Library", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
    BottomTab("review", "Review", Icons.Outlined.School, Icons.Filled.School),
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
    val showBottomBar = currentRoute in setOf("library", "review", "search", "settings")

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
                    onDiscover = { navController.navigate("search") }
                )
            }
            composable("review") {
                ReviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSource = { bookId -> navController.navigate("reader/$bookId") }
                )
            }
            composable("search") { DiscoverScreen() }
            composable("settings") {
                SettingsScreen(
                    onManageBlockedApps = { navController.navigate("blocked_apps") },
                    onPermissions = { navController.navigate("permissions") },
                    onUsageAudit = { navController.navigate("usage_audit") }
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
            composable("reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: "last"
                ReaderScreen(bookId = bookId, onBack = { navController.popBackStack() })
            }
        }
    }
}