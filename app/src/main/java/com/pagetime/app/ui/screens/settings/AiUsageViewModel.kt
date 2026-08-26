package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.AiUsageStats
import com.pagetime.app.data.local.AiSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AiUsageViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PageTimeApp).container

    val stats = container.aiUsageRepository.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiUsageStats())

    val aiSettings = container.settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())
}
