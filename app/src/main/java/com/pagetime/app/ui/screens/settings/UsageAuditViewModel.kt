package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.UsageEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class UsageAuditViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val context = app.applicationContext
    private val protectionRefresh = MutableStateFlow(0L)

    val balanceSeconds: StateFlow<Long> = container.balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val earnedToday: StateFlow<Long> = container.usageRepository.earnedToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val liveSpentToday: StateFlow<Long> = container.usageRepository.liveSpentToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val reconciledToday: StateFlow<Long> = container.usageRepository.reconciledToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val blockedToday: StateFlow<Long> = container.usageRepository.blockedToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val recentEvents: StateFlow<List<UsageEventEntity>> = container.usageRepository.recent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastReconcileAt: StateFlow<Long?> = container.settingsRepository.lastUsageReconcileAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val serviceConnected: StateFlow<Boolean> = container.blockController.serviceConnected

    val protectionStatus: StateFlow<ProtectionStatus> = combine(
        serviceConnected,
        lastReconcileAt,
        protectionRefresh
    ) { connected, lastReconcile, _ ->
        ProtectionStatus(
            accessibilityEnabled = isAccessibilityServiceEnabled(context),
            overlayEnabled = android.provider.Settings.canDrawOverlays(context),
            usageAccessEnabled = hasUsageAccessPermission(context),
            serviceConnected = connected,
            lastReconcileAt = lastReconcile
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProtectionStatus()
    )

    fun reconcileNow() {
        container.usageReconciler.requestReconcile()
    }

    fun refreshProtectionStatus() {
        protectionRefresh.value++
        container.usageReconciler.requestReconcile()
    }
}

data class ProtectionStatus(
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val usageAccessEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
    val lastReconcileAt: Long? = null
)