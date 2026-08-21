package com.pagetime.app.ui.screens.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedAppsViewModel(app: Application) : AndroidViewModel(app) {

    data class InstalledApp(val packageName: String, val label: String)

    private val container = (app as PageTimeApp).container
    private val repo = container.blockedAppRepository

    val blockedPackages = repo.observeEnabled()
        .map { apps -> apps.map { it.packageName }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installed = _installed.asStateFlow()

    init {
        viewModelScope.launch { _installed.value = loadLaunchableApps(app) }
    }

    fun toggle(app: InstalledApp, blocked: Boolean) {
        viewModelScope.launch { repo.setBlocked(app.packageName, app.label, blocked) }
    }

    private suspend fun loadLaunchableApps(context: Context): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val own = context.packageName
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo.packageName
                    if (pkg == own) return@mapNotNull null
                    InstalledApp(pkg, ri.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
}
