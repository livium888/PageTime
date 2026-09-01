package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LlmProviderKind
import com.pagetime.app.data.LumenModelStatus
import com.pagetime.app.data.LumenModelStore
import com.pagetime.app.data.learning.GeminiModel
import com.pagetime.app.data.learning.GenerationMode
import com.pagetime.app.data.local.AiAnalysisLevel
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppSettingsRow
import com.pagetime.app.ui.SectionHeader
import com.pagetime.app.ui.formatMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageBlockedApps: () -> Unit,
    onPermissions: () -> Unit,
    onUsageAudit: () -> Unit,
    onAiUsage: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val balanceSeconds by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsStateWithLifecycle()
    val ratio by viewModel.ratio.collectAsStateWithLifecycle()
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val helpEnabled by viewModel.helpEnabled.collectAsStateWithLifecycle()
    val llmProvider by viewModel.llmProvider.collectAsStateWithLifecycle()
    val lumenModelStatus by viewModel.lumenModelStatus.collectAsStateWithLifecycle()
    val geminiViewModel: GeminiSettingsViewModel = viewModel()
    val geminiModels by geminiViewModel.models.collectAsStateWithLifecycle()
    val selectedGeminiModel by geminiViewModel.selectedModel.collectAsStateWithLifecycle()
    val geminiHasUserKey by geminiViewModel.hasUserKey.collectAsStateWithLifecycle()
    val geminiStatus by geminiViewModel.status.collectAsStateWithLifecycle()
    var geminiKeyInput by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppCard {
                Text("Your time", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Browse balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatMinutes(balanceSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total reading", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMinutes(totalReadingSeconds), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text("Reading rate", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1 minute of reading earns ${"%.1f".format(ratio)} minutes of browsing",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = ratio.toFloat(),
                    onValueChange = { viewModel.setRatio(it.toDouble()) },
                    valueRange = 0.5f..3.0f,
                    steps = 4
                )
            }

            SectionHeader("Protection")
            AppSettingsRow(
                icon = Icons.Outlined.Block,
                label = "Manage blocked apps",
                onClick = onManageBlockedApps
            )
            AppSettingsRow(
                icon = Icons.Outlined.History,
                label = "Usage history & protection",
                onClick = onUsageAudit
            )
            AppSettingsRow(
                icon = Icons.Outlined.AccessibilityNew,
                label = "Permissions & setup",
                onClick = onPermissions
            )
            SectionHeader("Comprehension")
            Spacer(Modifier.height(4.dp))

            AiAnalysisSettingsCard(
                level = aiSettings.analysisLevel,
                onSelect = viewModel::setAiAnalysisLevel
            )
            GenerationModeSettingsCard(
                mode = aiSettings.generationMode,
                onSelect = viewModel::setGenerationMode
            )
            AppSettingsRow(
                icon = Icons.Outlined.History,
                label = "AI usage & statistics",
                onClick = onAiUsage
            )

            SectionHeader("Slip box")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Explain slip-box actions", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (helpEnabled) {
                                "Before Link, Connect, or File behind runs, you'll get a short\n" +
                                    "explanation and a confirmation. Leave this on while you learn\n" +
                                    "the Zettelkasten method."
                            } else {
                                "Help is off — Link, Connect, and File behind run immediately\n" +
                                    "with no explanation."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = helpEnabled,
                        onCheckedChange = { viewModel.setHelpEnabled(it) }
                    )
                }
            }

            LlmProviderSettingsCard(
                provider = llmProvider,
                onSelect = viewModel::setLlmProvider
            )

            OfflineModelSettingsCard(
                status = lumenModelStatus,
                onDownload = viewModel::downloadOfflineModel,
                onDelete = viewModel::deleteOfflineModel
            )

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
        }
    }
}

@Composable
private fun LlmProviderSettingsCard(
    provider: LlmProviderKind,
    onSelect: (LlmProviderKind) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI provider", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose where optional AI requests run. Offline mode is ready for a " +
                    "downloaded local model and will never send book text to Gemini " +
                    "automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LlmProviderKind.entries.forEach { option ->
                    FilterChip(
                        selected = option == provider,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            Text(
                provider.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (provider == LlmProviderKind.OFFLINE) {
                Text(
                    "Capture will draft cards with the downloaded model below. Without " +
                        "one, capture falls back to the plain on-device draft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineModelSettingsCard(
    status: LumenModelStatus,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline model", style = MaterialTheme.typography.titleMedium)
            Text(
                "${LumenModelStore.MODEL_LABEL} — an open Apache-2.0 model that runs " +
                    "entirely on this device. Book text and prompts never leave the phone. " +
                    "Download once over Wi-Fi (~${LumenModelStore.MODEL_SIZE_MB} MB); the app " +
                    "itself stays small either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                is LumenModelStatus.NotDownloaded -> {
                    Text(
                        "Not downloaded — ${LumenModelStore.MODEL_SIZE_MB} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download model")
                    }
                }
                is LumenModelStatus.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading… ${(status.fraction * 100).toInt()}% — keep the app open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is LumenModelStatus.Ready -> {
                    Text(
                        "Installed — ${LumenModelStore.MODEL_SIZE_MB} MB. Capture now drafts " +
                            "cards on-device when Offline model is selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete model")
                    }
                }
                is LumenModelStatus.Failed -> {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry download")
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAnalysisSettingsCard(
    level: AiAnalysisLevel,
    onSelect: (AiAnalysisLevel) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatic AI analysis", style = MaterialTheme.typography.titleMedium)
            Text(
                "The reader keeps tracking progress locally. Each chapter is analyzed once — cards and the concept map are generated and cached, so later checkpoints never re-send the same text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiAnalysisLevel.entries.forEach { option ->
                    FilterChip(
                        selected = option == level,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                "${level.label}: ${level.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun GenerationModeSettingsCard(
    mode: GenerationMode,
    onSelect: (GenerationMode) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How cards & concepts are built", style = MaterialTheme.typography.titleMedium)
            Text(
                "AI-assisted prefers Gemini for richer cards and concepts. On-device first keeps everything local and only uses Gemini when the local pass comes up empty. Either way each chapter is processed once and cached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GenerationMode.entries.forEach { option ->
                    FilterChip(
                        selected = option == mode,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun GeminiSettingsCard(
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    hasUserKey: Boolean,
    models: List<GeminiModel>,
    selectedModel: String,
    modelMenuExpanded: Boolean,
    onModelMenuExpandedChange: (Boolean) -> Unit,
    onSelectModel: (GeminiModel) -> Unit,
    status: GeminiSettingsStatus,
    onSaveKey: () -> Unit,
    onTestSavedKey: () -> Unit,
    onClearKey: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Explain Back with Gemini", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Add your Gemini API key for concept explanations and feedback. It is stored only on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = onKeyInputChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (hasUserKey) "Replace saved API key" else "Gemini API key") },
                placeholder = { Text("AIza...") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    if (hasUserKey) Text("A key is saved securely; it is never displayed.")
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (hasUserKey && keyInput.isBlank()) onTestSavedKey else onSaveKey,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (hasUserKey && keyInput.isBlank()) "Test saved key" else "Save & test")
                }
                if (hasUserKey) {
                    OutlinedButton(onClick = onClearKey, modifier = Modifier.weight(1f)) {
                        Text("Clear key")
                    }
                }
            }
            if (models.isNotEmpty()) {
                Text("Model", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(
                        onClick = { onModelMenuExpandedChange(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { onModelMenuExpandedChange(false) },
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName)
                                        if (model.description.isNotBlank()) {
                                            Text(
                                                model.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                },
                                onClick = { onSelectModel(model) }
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (status) {
                        GeminiSettingsStatus.Idle -> "Not tested"
                        GeminiSettingsStatus.Loading -> "Testing connection..."
                        is GeminiSettingsStatus.Ready -> status.message
                        is GeminiSettingsStatus.Error -> status.message
                    },
                    color = when (status) {
                        is GeminiSettingsStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh Gemini models")
                }
            }
        }
    }
}
