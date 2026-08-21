package com.pagetime.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pagetime.app.ui.screens.library.LibraryScreen
import com.pagetime.app.ui.screens.reader.ReaderScreen
import com.pagetime.app.ui.screens.discover.DiscoverScreen
import com.pagetime.app.ui.screens.settings.BlockedAppsScreen
import com.pagetime.app.ui.screens.settings.PermissionsScreen
import com.pagetime.app.ui.screens.settings.SettingsScreen

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    BottomTab("library", "Library", Icons.Outlined.MenuBook),
    BottomTab("search", "Discover", Icons.Outlined.Search),
    BottomTab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun PageTimeAppUi(openReader: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf("library", "search", "settings")

    LaunchedEffect(openReader) {
        if (openReader) {
            navController.navigate("reader/last") { launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
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
            composable("search") { DiscoverScreen() }
            composable("settings") {
                SettingsScreen(
                    onManageBlockedApps = { navController.navigate("blocked_apps") },
                    onPermissions = { navController.navigate("permissions") }
                )
            }
            composable("blocked_apps") { BlockedAppsScreen(onBack = { navController.popBackStack() }) }
            composable("permissions") { PermissionsScreen(onBack = { navController.popBackStack() }) }
            composable("reader/{bookId}") { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: "last"
                ReaderScreen(bookId = bookId, onBack = { navController.popBackStack() })
            }
        }
    }
}
