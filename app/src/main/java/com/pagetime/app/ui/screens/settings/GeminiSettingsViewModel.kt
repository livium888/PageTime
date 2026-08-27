package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.GeminiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GeminiSettingsStatus {
    data object Idle : GeminiSettingsStatus
    data object Loading : GeminiSettingsStatus
    data class Ready(val message: String) : GeminiSettingsStatus
    data class Error(val message: String) : GeminiSettingsStatus
}

class GeminiSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val client: GeminiLearningClient? =
        (app as? PageTimeApp)?.container?.geminiLearningClient

    private val _models = MutableStateFlow<List<GeminiModel>>(emptyList())
    val models = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow(client?.currentModel().orEmpty())
    val selectedModel = _selectedModel.asStateFlow()

    private val _hasUserKey = MutableStateFlow(readHasUserKey())
    val hasUserKey = _hasUserKey.asStateFlow()

    private fun readHasUserKey(): Boolean =
        runCatching { client?.hasUserKey() == true }.getOrDefault(false)

    private val _status = MutableStateFlow<GeminiSettingsStatus>(GeminiSettingsStatus.Idle)
    val status = _status.asStateFlow()

    fun saveKey(value: String) {
        val key = value.trim()
        if (key.isBlank()) {
            _status.value = GeminiSettingsStatus.Error("Enter a Gemini API key first")
            return
        }
        val activeClient = client ?: run {
            _status.value = GeminiSettingsStatus.Error("AI settings are unavailable right now")
            return
        }
        runCatching { activeClient.saveUserApiKey(key) }
            .onFailure { error ->
                _status.value = GeminiSettingsStatus.Error(
                    error.message ?: "Could not securely save the API key"
                )
                return
            }
        _hasUserKey.value = true
        _status.value = GeminiSettingsStatus.Ready("Key saved securely. Testing connection…")
        refreshModels()
    }

    fun clearKey() {
        val activeClient = client ?: return
        runCatching { activeClient.clearUserApiKey() }
            .onFailure { error ->
                _status.value = GeminiSettingsStatus.Error(
                    error.message ?: "Could not clear the saved API key"
                )
                return
            }
        _hasUserKey.value = false
        _models.value = emptyList()
        _selectedModel.value = activeClient.currentModel()
        _status.value = GeminiSettingsStatus.Ready("User key removed")
    }

    fun selectModel(model: GeminiModel) {
        val activeClient = client ?: return
        runCatching { activeClient.setModel(model.id) }
            .onFailure { error ->
                _status.value = GeminiSettingsStatus.Error(
                    error.message ?: "Could not save the model"
                )
                return
            }
        _selectedModel.value = model.id
        _status.value = GeminiSettingsStatus.Ready("Using ${model.displayName}")
    }

    fun refreshModels() {
        val activeClient = client ?: run {
            _status.value = GeminiSettingsStatus.Error("AI settings are unavailable right now")
            return
        }
        if (!activeClient.isConfigured) {
            _status.value = GeminiSettingsStatus.Error("Add a Gemini API key to load models")
            return
        }
        viewModelScope.launch {
            _status.value = GeminiSettingsStatus.Loading
            runCatching { activeClient.testConnection() }
                .onSuccess { result ->
                    _models.value = result.models
                    _selectedModel.value = result.selectedModel
                    _status.value = GeminiSettingsStatus.Ready(
                        "Connected · ${result.models.size} generation models available"
                    )
                }
                .onFailure { error ->
                    _status.value = GeminiSettingsStatus.Error(
                        error.message ?: "Could not connect to Gemini"
                    )
                }
        }
    }
}
