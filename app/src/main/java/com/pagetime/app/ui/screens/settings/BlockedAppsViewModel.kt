package com.pagetime.app.ui.screens.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.blocker.AppAllowlist
import com.pagetime.app.blocker.AppMode
import com.pagetime.app.domain.GateState
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

    /** Wall-clock (epoch millis) when the non-cancellable hard lock ends (0 = none). */
    val hardLockUntil = settingsRepo.settings
        .map { it.hardLockUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Decides whether apps may be taken OFF the list right now. */
    val gate = container.balanceManager.gate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateState.Unknown)

    /** Blocklist or allowlist — see [AppMode]. */
    val appMode = settingsRepo.settings
        .map { it.appMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppMode.BLOCKLIST)

    /** The apps chosen for allowlist mode, at most [AppAllowlist.MAX_APPS]. */
    val allowedApps = settingsRepo.settings
        .map { it.allowedApps }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** When the free setup window for the app allowlist closes (0 = none). */
    val appAllowlistSetupGraceUntil = settingsRepo.settings
        .map { it.appAllowlistSetupGraceUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _essentials = MutableStateFlow<Set<String>>(emptySet())

    /** Never blocked in allowlist mode and never counted against the five. */
    val essentials = _essentials.asStateFlow()

    /** Set when the last change was refused, so the screen can say why. */
    private val _refusal = MutableStateFlow<String?>(null)
    val refusal = _refusal.asStateFlow()

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
        viewModelScope.launch(Dispatchers.IO) {
            val classifier = container.appClassifier
            _essentials.value = classifier.essentials() + AppAllowlist.KNOWN_AUTHENTICATORS
        }
    }

    /**
     * Switches between blocking chosen apps and allowing only chosen apps.
     * Leaving the allowlist is loosening and can be refused; the balance
     * manager is where that rule lives.
     */
    fun setAppMode(mode: AppMode) {
        viewModelScope.launch {
            val ok = container.balanceManager.setAppMode(mode)
            _refusal.value = if (ok) null else
                "Switching back to blocking only some apps opens everything else, so it waits " +
                    "until today's reading is done."
        }
    }

    /** Allows or disallows [app] in allowlist mode. */
    fun setAllowed(app: InstalledApp, allowed: Boolean) {
        viewModelScope.launch {
            if (allowed) {
                val ok = container.balanceManager.addAllowedApp(app.packageName)
                _refusal.value = if (ok) null else
                    "Couldn't add ${app.label}: you can allow at most ${AppAllowlist.MAX_APPS} " +
                        "apps, and after the setup window adding one waits for today's reading."
            } else {
                container.balanceManager.removeAllowedApp(app.packageName)
                _refusal.value = null
            }
        }
    }

    fun toggle(app: InstalledApp, blocked: Boolean) {
        viewModelScope.launch { repo.setBlocked(app.packageName, app.label, blocked) }
    }

    /**
     * Commit to the block for [minutes] with no way to lift it early: while a
     * hard lock is active every soft escape is disabled — the per-app toggles
     * included — "no matter what". It outranks today's reading too.
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
