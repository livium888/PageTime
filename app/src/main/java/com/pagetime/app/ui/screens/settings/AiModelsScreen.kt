package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.ui.AppSettingsRow
import com.pagetime.app.ui.SectionHeader

/**
 * Everything about the models this app talks to, on one screen.
 *
 * These controls used to live inside Settings under a heading called "Slip box"
 * — which is where the capture prompt and the slip-box help switch also were —
 * so a section that named one feature held the API key, two model downloads,
 * the provider switch, the analysis level and the notification toggle as well.
 * Nobody looking for the API key would think to open "Slip box".
 *
 * The screen is grouped by what the reader is actually choosing:
 *
 *  - **Provider** — where generation runs at all: on this phone or in the cloud.
 *  - **On-device model** — the two weights files, their downloads and health.
 *  - **Cloud key** — the Gemini key and the model it talks to.
 *  - **How cards are built** — the analysis level, the generation mode, how much
 *    of a page a capture reads, and the prompt it is asked for.
 *  - **Usage** — what all of it has cost, in requests and tokens.
 *
 * The card composables themselves still live in SettingsScreen.kt next to the
 * gate controls they were written beside; only the decision of where they
 * appear moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelsScreen(
    onBack: () -> Unit,
    onAiUsage: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val llmProvider by viewModel.llmProvider.collectAsStateWithLifecycle()
    val lumenModelStatus by viewModel.lumenModelStatus.collectAsStateWithLifecycle()
    val offlineAiProblem by viewModel.offlineAiProblem.collectAsStateWithLifecycle()
    val lumenPrompt by viewModel.lumenPrompt.collectAsStateWithLifecycle()
    val lumenPromptIsCustom by viewModel.lumenPromptIsCustom.collectAsStateWithLifecycle()
    val geminiViewModel: GeminiSettingsViewModel = viewModel()
    val geminiModels by geminiViewModel.models.collectAsStateWithLifecycle()
    val selectedGeminiModel by geminiViewModel.selectedModel.collectAsStateWithLifecycle()
    val geminiHasUserKey by geminiViewModel.hasUserKey.collectAsStateWithLifecycle()
    val geminiStatus by geminiViewModel.status.collectAsStateWithLifecycle()
    var geminiKeyInput by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // A cheap HEAD against the model host, best-effort and silent on failure.
    // It checks the model, so it belongs with the model's own screen rather
    // than on Settings, which no longer shows it.
    LaunchedEffect(Unit) { viewModel.checkForModelUpdate() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI & models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader("Provider")
            LlmProviderSettingsCard(
                provider = llmProvider,
                onSelect = viewModel::setLlmProvider
            )

            SectionHeader("On-device model")
            OfflineModelSettingsCard(
                status = lumenModelStatus,
                offlineProblem = offlineAiProblem,
                onRetryOfflineAi = viewModel::retryOfflineAi,
                downloadStats = viewModel.downloadStats.collectAsStateWithLifecycle().value,
                modelUrl = viewModel.lumenModelUrl.collectAsStateWithLifecycle().value,
                onSetModelUrl = viewModel::setLumenModelUrl,
                cloudRescue = viewModel.lumenCloudRescue.collectAsStateWithLifecycle().value,
                onSetCloudRescue = viewModel::setLumenCloudRescue,
                onDownload = viewModel::downloadOfflineModel,
                onCheckForUpdate = viewModel::checkForModelUpdate,
                onDelete = viewModel::deleteOfflineModel
            )
            EmbeddingModelSettingsCard(
                status = viewModel.embeddingModelStatus.collectAsStateWithLifecycle().value,
                selfTest = viewModel.embeddingSelfTest.collectAsStateWithLifecycle().value,
                selfTestRunning =
                    viewModel.embeddingSelfTestRunning.collectAsStateWithLifecycle().value,
                pending = viewModel.embeddingPending.collectAsStateWithLifecycle().value,
                indexing = viewModel.embeddingIndexing.collectAsStateWithLifecycle().value,
                onDownload = viewModel::downloadEmbeddingModel,
                onDelete = viewModel::deleteEmbeddingModel,
                onSelfTest = viewModel::runEmbeddingSelfTest,
                onIndexAll = viewModel::indexAllCards,
                onRefreshPending = viewModel::refreshEmbeddingPending,
            )

            SectionHeader("Cloud key")
            GeminiSettingsCard(
                keyInput = geminiKeyInput,
                onKeyInputChange = { geminiKeyInput = it },
                hasUserKey = geminiHasUserKey,
                models = geminiModels,
                selectedModel = selectedGeminiModel,
                modelMenuExpanded = modelMenuExpanded,
                onModelMenuExpandedChange = { modelMenuExpanded = it },
                onSelectModel = {
                    geminiViewModel.selectModel(it)
                    modelMenuExpanded = false
                },
                status = geminiStatus,
                onSaveKey = { geminiViewModel.saveKey(geminiKeyInput) },
                onTestSavedKey = geminiViewModel::testSavedKey,
                onClearKey = {
                    geminiViewModel.clearKey()
                    geminiKeyInput = ""
                },
                onRefresh = geminiViewModel::refreshModels
            )

            SectionHeader("How cards are built")
            AiAnalysisSettingsCard(
                level = aiSettings.analysisLevel,
                onSelect = viewModel::setAiAnalysisLevel
            )
            GenerationModeSettingsCard(
                mode = aiSettings.generationMode,
                onSelect = viewModel::setGenerationMode
            )
            CaptureSizeCard(
                captureChars = viewModel.captureChars.collectAsStateWithLifecycle().value,
                onSelect = viewModel::setCaptureChars,
            )
            CapturePromptCard(
                prompt = lumenPrompt,
                isCustom = lumenPromptIsCustom,
                onSave = viewModel::setLumenPrompt,
                onReset = viewModel::resetLumenPrompt
            )

            SectionHeader("Usage")
            AppSettingsRow(
                icon = Icons.Outlined.History,
                label = "AI usage & statistics",
                subtitle = "Requests, tokens and characters sent",
                onClick = onAiUsage
            )
        }
    }
}
