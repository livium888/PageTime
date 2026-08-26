package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.learning.GenerationMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    val balanceSeconds = container.balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalReadingSeconds = container.balanceManager.totalReadingSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val ratio = container.balanceManager.ratio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0)

    val aiSettings = container.settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.pagetime.app.data.local.AiSettings())

    fun setRatio(value: Double) {
        viewModelScope.launch { container.balanceManager.setRatio(value) }
    }

    fun setAiAnalysisLevel(level: com.pagetime.app.data.local.AiAnalysisLevel) {
        viewModelScope.launch { container.settingsRepository.setAiAnalysisLevel(level) }
    }

    fun setGenerationMode(mode: GenerationMode) {
        viewModelScope.launch { container.settingsRepository.setGenerationMode(mode) }
    }
}
