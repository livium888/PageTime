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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.LlmProviderKind
import com.pagetime.app.data.LumenModelStatus
import com.pagetime.app.data.LumenModelStore
import com.pagetime.app.data.learning.GeminiModel
import com.pagetime.app.data.learning.GenerationMode
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.AiAnalysisLevel
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppSettingsRow
import com.pagetime.app.BuildConfig
import com.pagetime.app.data.LumenLocalDraft
import com.pagetime.app.data.LlmTokenBudget
import com.pagetime.app.data.LumenAiPrompts
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
    val lumenPrompt by viewModel.lumenPrompt.collectAsStateWithLifecycle()
    val lumenPromptIsCustom by viewModel.lumenPromptIsCustom.collectAsStateWithLifecycle()
    val geminiViewModel: GeminiSettingsViewModel = viewModel()
    val geminiModels by geminiViewModel.models.collectAsStateWithLifecycle()
    val selectedGeminiModel by geminiViewModel.selectedModel.collectAsStateWithLifecycle()
    val geminiHasUserKey by geminiViewModel.hasUserKey.collectAsStateWithLifecycle()
    val geminiStatus by geminiViewModel.status.collectAsStateWithLifecycle()
    var geminiKeyInput by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // Newest crash log from filesDir/crash, so the user can copy it to support
    // without adb. Read once when Settings opens.
    var crashLogText by remember { mutableStateOf<String?>(null) }
    val settingsContext = LocalContext.current
    LaunchedEffect(Unit) {
        crashLogText =
            PageTimeApp.crashDirOf(settingsContext)
                .listFiles { file -> file.name.startsWith("crash-") && file.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
                ?.takeIf { it.length() > 0 }
                ?.readText()
                ?.take(4_000)
    }

    // Cheap HEAD against the model host; best-effort and silent on failure.
    LaunchedEffect(Unit) { viewModel.checkForModelUpdate() }

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
            CapturePromptCard(
                prompt = lumenPrompt,
                isCustom = lumenPromptIsCustom,
                onSave = viewModel::setLumenPrompt,
                onReset = viewModel::resetLumenPrompt
            )
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
                downloadStats = viewModel.downloadStats.collectAsStateWithLifecycle().value,
                modelUrl = viewModel.lumenModelUrl.collectAsStateWithLifecycle().value,
                onSetModelUrl = viewModel::setLumenModelUrl,
                onDownload = viewModel::downloadOfflineModel,
                onCheckForUpdate = viewModel::checkForModelUpdate,
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

            SectionHeader("Support")
            AppVersionCard()
            CrashDiagnosticsCard(crashLogText = crashLogText)
        }
    }
}

/**
 * The installed build's version. Every Actions artifact carries the same file
 * name, so this is the only way to tell a fresh install from a stale download
 * without reading the APK.
 */
/**
 * Lets the reader tailor the prompt the offline model is given for a capture.
 * The passage is still trimmed and the prompt still measured before it reaches
 * the model, so a hand-written prompt can produce a poor card but cannot push
 * the request past the budget that used to kill the process.
 */
@Composable
private fun CapturePromptCard(
    prompt: String,
    isCustom: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var draft by remember(prompt) { mutableStateOf(prompt) }
    var expanded by remember { mutableStateOf(false) }
    val problem = LumenAiPrompts.templateProblem(draft)
    val tokens = remember(draft) { LumenAiPrompts.worstCaseTokens(draft) }
    val budget = LlmTokenBudget.inputBudget(LumenLocalDraft.REPLY_TOKENS)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Card capture prompt", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isCustom) "Yours" else "The built-in prompt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Edit")
                }
            }
            if (expanded) {
                Text(
                    "What the offline model is asked for when you capture a card. " +
                        "${LumenAiPrompts.PASSAGE_TOKEN} is replaced with the passage you " +
                        "are reading and ${LumenAiPrompts.BOOK_TOKEN} with the book's title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    minLines = 8,
                    maxLines = 20,
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    when {
                        problem != null -> problem
                        tokens > budget ->
                            "About $tokens tokens on a full page, over the $budget the model " +
                                "can read. Long captures will fall back to a plain draft."
                        else ->
                            "About $tokens tokens on a full page, of $budget the model can read."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (problem != null || tokens > budget) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(draft) },
                        enabled = problem == null && draft != prompt
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = {
                            onReset()
                            draft = LumenAiPrompts.DEFAULT_CARD_TEMPLATE
                        },
                        enabled = isCustom || draft != LumenAiPrompts.DEFAULT_CARD_TEMPLATE
                    ) {
                        Text("Restore default")
                    }
                }
                Text(
                    "The retry that runs when a reply is unusable always uses the built-in " +
                        "prompt, so a tailored one that misfires still lands a card.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppVersionCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Installed build", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CrashDiagnosticsCard(crashLogText: String?) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Crash diagnostics", style = MaterialTheme.typography.titleMedium)
            if (crashLogText == null) {
                Text(
                    "No crash log found. If the app crashes, reopen it and come back here — " +
                        "the log appears automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Most recent crash log — copy it and send it to support:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    crashLogText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val clip = ClipData.newPlainText("PageTime crash log", crashLogText)
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(clip)
                    }) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, crashLogText)
                        }
                        context.startActivity(Intent.createChooser(send, "Share crash log"))
                    }) {
                        Text("Share")
                    }
                }
            }
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
    downloadStats: LumenDownloadStats?,
    modelUrl: String,
    onSetModelUrl: (String?) -> Unit,
    onDownload: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline model", style = MaterialTheme.typography.titleMedium)
            Text(
                "${LumenModelStore.MODEL_LABEL} — Google's Gemma model built for this " +
                    "runtime, running entirely on this device. Book text and prompts never " +
                    "leave the phone. Download once over Wi-Fi (~${LumenModelStore.MODEL_SIZE_MB} MB); " +
                    "the app itself stays small either way.",
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
                is LumenModelStatus.UpdateAvailable -> {
                    Text(
                        "A newer version of the offline model is available " +
                            "(~${(status.remoteBytes / 1_048_576).toInt()} MB). Update keeps " +
                            "capture quality current; the installed model keeps working " +
                            "until the new one finishes verifying.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Update model")
                    }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete model")
                    }
                }
                is LumenModelStatus.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        buildString {
                            append("Downloading… ")
                            append(formatModelMb(status.downloadedBytes))
                            append(" of ")
                            append(formatModelMb(status.totalBytes))
                            downloadStats?.let { stats ->
                                if (stats.rateBytesPerSec > 0) {
                                    append(" — ")
                                    append(formatModelMb(stats.rateBytesPerSec))
                                    append("/s")
                                }
                            }
                            append(" — keep the app open")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is LumenModelStatus.Ready -> {
                    Text(
                        "Installed — ${(status.bytes / 1_048_576).toInt()} MB. Capture now drafts " +
                            "cards on-device when Offline model is selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = onCheckForUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Check for updates")
                    }
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ModelSourcePicker(modelUrl = modelUrl, onSetModelUrl = onSetModelUrl)
        }
    }
}

/**
 * Which weights to download.
 *
 * The built-in model can paraphrase a passage but not reliably state the idea
 * behind it. Parameters are the lever left, so a larger model is offered — and
 * the address is editable rather than fixed, because whether a given URL
 * serves the file it claims to is the one thing that cannot be checked from a
 * build server. A wrong address costs a visible download error: the size is
 * taken from the server's own headers and the file is structurally checked
 * before the runtime ever opens it.
 */
@Composable
private fun ModelSourcePicker(
    modelUrl: String,
    onSetModelUrl: (String?) -> Unit,
) {
    var draft by rememberSaveable(modelUrl) { mutableStateOf(modelUrl) }
    val isBuiltIn = modelUrl == LumenModelStore.MODEL_URL

    Text("Which model", style = MaterialTheme.typography.titleSmall)
    Text(
        if (isBuiltIn) {
            "Using the built-in ${LumenModelStore.MODEL_LABEL}."
        } else {
            "Using a model you chose. Switching deletes the installed weights, " +
                "so the new one downloads fresh."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "${LumenModelStore.ALTERNATE_MODEL_LABEL} is the alternative on offer. It is " +
            "three times the built-in model's weights, and loading needs roughly " +
            "1.7x the file in FREE memory — about 2.7 GB for this one. Check the " +
            "free figure in the capture log before spending the download: under it, " +
            "the model is refused and capture falls back to the plain draft.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Any MediaPipe .task bundle works here — paste a direct download link. " +
            "A wrong address fails visibly: the size is taken from the server and " +
            "the file is structurally checked before the runtime opens it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Model download URL") },
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onSetModelUrl(draft.trim().takeIf { it.isNotBlank() }) },
            enabled = draft.trim().isNotBlank() && draft.trim() != modelUrl,
            modifier = Modifier.weight(1f),
        ) {
            Text("Use this model")
        }
        OutlinedButton(
            onClick = { draft = LumenModelStore.ALTERNATE_MODEL_URL },
            modifier = Modifier.weight(1f),
        ) {
            Text("Qwen 1.5B")
        }
    }
    if (!isBuiltIn) {
        OutlinedButton(
            onClick = { onSetModelUrl(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back to the built-in model")
        }
    }
}

/** "12.4 MB" / "3.2 MB" — live byte counts, not a percentage. */
private fun formatModelMb(bytes: Long): String =
    "%.1f".format(bytes / 1_048_576.0)

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
