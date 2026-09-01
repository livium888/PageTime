package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LlmProviderKind
import com.pagetime.app.data.LumenModelStatus
import com.pagetime.app.data.learning.GenerationMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
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

    val llmProvider = container.settingsRepository.settings
        .map { it.llmProvider }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmProviderKind.GEMINI)

    val helpEnabled = container.settingsRepository.settings
        .map { it.helpEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val lumenModelStatus =
        container.lumenModelStore.status
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LumenModelStatus.NotDownloaded)

    fun setRatio(value: Double) {
        viewModelScope.launch { container.balanceManager.setRatio(value) }
    }

    fun setAiAnalysisLevel(level: com.pagetime.app.data.local.AiAnalysisLevel) {
        viewModelScope.launch { container.settingsRepository.setAiAnalysisLevel(level) }
    }

    fun setGenerationMode(mode: GenerationMode) {
        viewModelScope.launch { container.settingsRepository.setGenerationMode(mode) }
    }

    fun setHelpEnabled(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHelpEnabled(value) }
    }

    fun setLlmProvider(provider: LlmProviderKind) {
        viewModelScope.launch { container.settingsRepository.setLlmProvider(provider) }
    }

    fun downloadOfflineModel() {
        viewModelScope.launch { container.lumenModelStore.download() }
    }

    fun checkForModelUpdate() {
        viewModelScope.launch { container.lumenModelStore.checkForUpdate() }
    }

    fun deleteOfflineModel() {
        viewModelScope.launch { container.lumenModelStore.deleteModel() }
    }
}
