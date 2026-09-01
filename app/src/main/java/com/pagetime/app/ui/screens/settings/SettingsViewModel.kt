package com.pagetime.app.ui.screens.settings

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LlmProviderKind
import com.pagetime.app.data.LumenModelStatus
import com.pagetime.app.data.learning.GenerationMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Live bytes of an in-flight model download, plus its current speed. */
data class LumenDownloadStats(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val rateBytesPerSec: Long,
    val sampledAtMs: Long,
)

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

    /**
     * Live download progress with a rolling speed estimate, derived from the
     * store's progress emissions. Speeds are measured between consecutive
     * updates (~512 KB apart), so a stalled connection visibly drops to 0.
     */
    val downloadStats: StateFlow<LumenDownloadStats?> =
        container.lumenModelStore.status
            .runningFold<LumenModelStatus, LumenDownloadStats?>(null) { previous, status ->
                val downloading = status as? LumenModelStatus.Downloading ?: return@runningFold null
                val now = SystemClock.elapsedRealtime()
                val rate =
                    previous
                        ?.takeIf { it.totalBytes == downloading.totalBytes && it.sampledAtMs > 0 }
                        ?.let { last ->
                            val deltaMs = (now - last.sampledAtMs).coerceAtLeast(1L)
                            val deltaBytes = downloading.downloadedBytes - last.downloadedBytes
                            if (deltaBytes > 0) deltaBytes * 1000L / deltaMs else 0L
                        }
                        ?: 0L
                LumenDownloadStats(
                    downloadedBytes = downloading.downloadedBytes,
                    totalBytes = downloading.totalBytes,
                    rateBytesPerSec = rate,
                    sampledAtMs = now,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
