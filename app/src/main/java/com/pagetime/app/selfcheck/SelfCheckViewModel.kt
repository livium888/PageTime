package com.pagetime.app.selfcheck

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.BuildConfig
import com.pagetime.app.PageTimeApp
import com.pagetime.app.selfcheck.SelfCheck.Result
import com.pagetime.app.selfcheck.SelfCheck.Scenario
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelfCheckViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    val scenarios: List<Scenario> = RuleScenarios.all() + DeviceScenarios.all(app, container)

    data class UiState(
        val results: Map<String, Result> = emptyMap(),
        val running: String? = null,
        val finished: Boolean = false,
        val copied: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun runAll() {
        if (_state.value.running != null) return
        _state.value = UiState(running = "starting")
        viewModelScope.launch {
            for (scenario in scenarios) {
                _state.update { it.copy(running = scenario.id) }
                val result = SelfCheck.runOne(scenario)
                _state.update { it.copy(results = it.results + (scenario.id to result)) }
            }
            _state.update { it.copy(running = null, finished = true) }
        }
    }

    /** The report for whoever is looking into a problem, copied to the clipboard. */
    fun copyReport() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val results = scenarios.mapNotNull { _state.value.results[it.id] }
            val crash = withContext(Dispatchers.IO) {
                PageTimeApp.crashDirOf(app)
                    .listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
                    ?.maxByOrNull { it.lastModified() }
                    ?.takeIf { it.length() > 0 }
                    ?.readText()?.take(3_000)
            }
            val header = listOf(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
                "Run at " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
            )
            val text = SelfCheck.report(results, header, crash)
            app.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("PageTime self-check", text))
            _state.update { it.copy(copied = true) }
        }
    }
}
