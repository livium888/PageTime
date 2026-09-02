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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedAppsViewModel(app: Application) : AndroidViewModel(app) {

    data class InstalledApp(val packageName: String, val label: String)

    /** One row of the blocking stats: how often and how long an app got through. */
    data class BlockStat(
        val packageName: String,
        val label: String,
        val blockedCount: Long,
        val spentSeconds: Long
    )

    private val container = (app as PageTimeApp).container
    private val repo = container.blockedAppRepository
    private val settingsRepo = container.settingsRepository
    private val usageRepo = container.usageRepository

    val blockedPackages = repo.observeEnabled()
        .map { apps -> apps.map { it.packageName }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Wall-clock (epoch millis) when the soft "block paused" grace ends (0 = none). */
    val quickDisableUntil = settingsRepo.settings
        .map { it.quickDisableUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Wall-clock (epoch millis) when the non-cancellable hard lock ends (0 = none). */
    val hardLockUntil = settingsRepo.settings
        .map { it.hardLockUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installed = _installed.asStateFlow()

    /** Human labels for every launchable app, so stats can name packages. */
    private val _packageLabels = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Blocking stats for the last 24h: blocked count + browse-seconds per app. */
    val blockedStats = combine(
        usageRepo.blockedCountsByPackageToday(),
        usageRepo.spentSecondsByPackageToday()
    ) { counts, spent ->
        val spentBy = spent.associate { it.packageName to it.total }
        val labels = _packageLabels.value
        counts
            .map { stat ->
                BlockStat(
                    packageName = stat.packageName,
                    label = labels[stat.packageName] ?: stat.packageName,
                    blockedCount = stat.total,
                    spentSeconds = spentBy[stat.packageName] ?: 0L
                )
            }
            .sortedWith(compareByDescending<BlockStat> { it.blockedCount }.thenBy { it.label })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _installed.value = loadLaunchableApps(app)
            _packageLabels.value = _installed.value.associate { it.packageName to it.label }
        }
    }

    fun toggle(app: InstalledApp, blocked: Boolean) {
        viewModelScope.launch { repo.setBlocked(app.packageName, app.label, blocked) }
    }

    /** Temporarily lift the block for [minutes]; cancelled by [cancelQuickDisable]. */
    fun quickDisable(minutes: Long) {
        viewModelScope.launch {
            // Starting a grace is only meaningful when there is no hard lock to defeat it.
            settingsRepo.clearHardLockUntil()
            settingsRepo.setQuickDisableUntil(
                System.currentTimeMillis() + minutes.coerceAtLeast(1L) * 60_000L
            )
        }
    }

    /** End the soft grace early and resume normal blocking. */
    fun cancelQuickDisable() {
        viewModelScope.launch { settingsRepo.clearQuickDisableUntil() }
    }

    /**
     * Commit to the block for [minutes] with no way to lift it early: a hard lock
     * clears any pending grace and, while it is active, disables every soft escape
     * (the quick-disable buttons and the per-app toggles) "no matter what".
     */
    fun hardLock(minutes: Long) {
        viewModelScope.launch {
            settingsRepo.clearQuickDisableUntil()
            settingsRepo.setHardLockUntil(
                System.currentTimeMillis() + minutes.coerceAtLeast(1L) * 60_000L
            )
        }
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
