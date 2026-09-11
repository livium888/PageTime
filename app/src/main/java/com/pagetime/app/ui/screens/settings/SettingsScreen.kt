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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import com.pagetime.app.data.LumenModelStore
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.embed.EmbeddingModelStatus
import com.pagetime.app.data.embed.EmbeddingModelStore
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
import com.pagetime.app.blocker.BlockScreenText
import com.pagetime.app.domain.GateState
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
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val readInLastDay by viewModel.readInLastDay.collectAsStateWithLifecycle()
    val emergencyThisWeek by viewModel.emergencyThisWeek.collectAsStateWithLifecycle()
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

                if (gate.enabled) {
                    if (gate.sessionActive) {
                        // Unspent app time is the only thing on this card
                        // worth looking at, so it gets the big number.
                        Text("App time left", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            BlockScreenText.countdown(gate.sessionRemainingSeconds),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Your apps are open. This only counts down while you are actually " +
                                "using them, and what is left keeps until you do. It is also " +
                                "the only time apps can be taken off the blocked list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (gate.canStartSession) {
                            Button(
                                onClick = { viewModel.startSession() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add ${BlockScreenText.span(gate.sessionLengthSeconds)} more")
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Read toward your next " +
                                    BlockScreenText.span(gate.sessionLengthSeconds),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                BlockScreenText.span(
                                    gate.sessionCostSeconds - gate.secondsToNextSession
                                ) + " of " + BlockScreenText.span(gate.sessionCostSeconds),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { gate.creditProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (gate.sessionsBanked > 1) {
                            Text(
                                "${gate.sessionsBanked} sessions banked — the most you can hold.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { viewModel.startSession() },
                            enabled = gate.canStartSession,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (gate.canStartSession) {
                                    "Start ${BlockScreenText.span(gate.sessionLengthSeconds)}"
                                } else {
                                    BlockScreenText.span(gate.secondsToNextSession) + " to go"
                                }
                            )
                        }
                    }
                }

                // Shown whichever rule is in force: what was actually read.
                // Not the same question as the credit counter above — that one
                // says what is left, this says what happened.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Read in the last 24 hours", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        BlockScreenText.span(readInLastDay),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total reading", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMinutes(totalReadingSeconds), style = MaterialTheme.typography.titleMedium)
                }
                if (emergencyThisWeek > 0) {
                    // The number, not a judgement about it. It is the only way
                    // to notice that the gate is set wrong for your life.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Emergency unlocks this week",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("$emergencyThisWeek", style = MaterialTheme.typography.titleMedium)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Earn the day", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${BlockScreenText.span(gate.sessionCostSeconds)} of reading buys " +
                                "${BlockScreenText.span(gate.sessionLengthSeconds)} of app time, " +
                                "spent only while you use them. No minute-for-minute trading, " +
                                "and no pause button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = gate.switchedOn,
                        onCheckedChange = { viewModel.setGateSwitchedOn(it) }
                    )
                }

                if (gate.windingDown) {
                    // The switch reads off; the gate is still on for a day.
                    // Saying so is the whole point — a delay the reader only
                    // discovers by being blocked would feel like a bug.
                    Text(
                        "Switching off in " + BlockScreenText.span(gate.secondsUntilDisabled) +
                            ". Until then the gate still applies. Turn it back on any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (gate.enabled) {
                    Spacer(Modifier.height(8.dp))
                    // Both sliders can only be moved in the strict direction
                    // outside a session. Making the gate cheaper is the same
                    // kind of escape as unblocking an app — a bigger one, in
                    // fact, since it dissolves the whole thing — so it costs
                    // the same: app time in hand.
                    if (!gate.canLoosenTheRules) {
                        Text(
                            "These can be made stricter any time. Making them easier needs " +
                                "app time in hand — the same price as unblocking an app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("Reading per session", style = MaterialTheme.typography.titleMedium)
                    Text(
                        BlockScreenText.span(gate.sessionCostSeconds),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The range is the FULL range, and the allowed direction is
                    // enforced when a value is committed instead of by clipping
                    // the travel to half the track. Clipping it pinned the thumb
                    // to the end of the range, which made every touch read as a
                    // large move the one way it could go: touching the price
                    // slider jumped the reading target halfway to the maximum,
                    // over and over. See [GateState.committedCostSeconds].
                    val costMinutes = (gate.sessionCostSeconds / 60).toFloat()
                    val lengthMinutes = (gate.sessionLengthSeconds / 60).toFloat()
                    val costFloor = (GateState.MIN_SESSION_COST_SECONDS / 60).toFloat()
                    val costCeiling = (GateState.MAX_SESSION_COST_SECONDS / 60).toFloat()
                    val lengthFloor = (GateState.MIN_SESSION_LENGTH_SECONDS / 60).toFloat()
                    val lengthCeiling = (GateState.MAX_SESSION_LENGTH_SECONDS / 60).toFloat()

                    Slider(
                        value = costMinutes.coerceIn(costFloor, costCeiling),
                        onValueChange = { proposed ->
                            val next = GateState.committedCostSeconds(
                                currentSeconds = gate.sessionCostSeconds,
                                proposedSeconds = proposed.toLong() * 60,
                                canLoosenTheRules = gate.canLoosenTheRules,
                            )
                            if (next != gate.sessionCostSeconds) {
                                viewModel.setSessionCostSeconds(next)
                            }
                        },
                        valueRange = costFloor..costCeiling,
                        // Already at the top of the range with no app time in
                        // hand, the control has nothing left it may do, so it is
                        // switched off rather than left to swallow input.
                        enabled = gate.canLoosenTheRules || costMinutes < costCeiling
                    )
                    Text("Session length", style = MaterialTheme.typography.titleMedium)
                    Text(
                        BlockScreenText.span(gate.sessionLengthSeconds),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = lengthMinutes.coerceIn(lengthFloor, lengthCeiling),
                        onValueChange = { proposed ->
                            val next = GateState.committedLengthSeconds(
                                currentSeconds = gate.sessionLengthSeconds,
                                proposedSeconds = proposed.toLong() * 60,
                                canLoosenTheRules = gate.canLoosenTheRules,
                            )
                            if (next != gate.sessionLengthSeconds) {
                                viewModel.setSessionLengthSeconds(next)
                            }
                        },
                        valueRange = lengthFloor..lengthCeiling,
                        enabled = gate.canLoosenTheRules || lengthMinutes > lengthFloor
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Browse balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatMinutes(balanceSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                cloudRescue = viewModel.lumenCloudRescue.collectAsStateWithLifecycle().value,
                onSetCloudRescue = viewModel::setLumenCloudRescue,
                onDownload = viewModel::downloadOfflineModel,
                onCheckForUpdate = viewModel::checkForModelUpdate,
                onDelete = viewModel::deleteOfflineModel
            )

            ReviewRemindersCard(
                enabled = viewModel.reviewReminders.collectAsStateWithLifecycle().value,
                onChange = viewModel::setReviewReminders,
            )

            CaptureSizeCard(
                captureChars = viewModel.captureChars.collectAsStateWithLifecycle().value,
                onSelect = viewModel::setCaptureChars,
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

/**
 * The retrieval model, and the button that proves it works.
 *
 * Separate from the language model on purpose. They are different files with
 * different jobs — one writes cards, one finds which cards are alike — and a
 * reader can want either without the other. Deleting one must not disturb the
 * other, and a reader whose phone cannot load the 554 MB language model can
 * still have working search from a 22 MB one.
 *
 * The self-test earns its place in the UI rather than living in a test suite.
 * Everything under it is already unit-tested, but only against fixtures: the
 * failures that survive to here — a vocabulary offset by a row, an export that
 * pools its own output — do not throw, and produce vectors of the right shape
 * whose neighbours are merely worse. Ten seconds on the reader's own phone is
 * the only place that can be caught.
 */
@Composable
private fun EmbeddingModelSettingsCard(
    status: EmbeddingModelStatus,
    selfTest: List<String>,
    selfTestRunning: Boolean,
    pending: Int,
    indexing: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelfTest: () -> Unit,
    onIndexAll: () -> Unit,
    onRefreshPending: () -> Unit,
) {
    // Counted when the model becomes ready rather than observed continuously:
    // it is one COUNT query, and nothing changes it except saving a card or
    // running the indexer, both of which refresh it themselves.
    LaunchedEffect(status) {
        if (status is EmbeddingModelStatus.Ready) onRefreshPending()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Finding related notes", style = MaterialTheme.typography.titleMedium)
            Text(
                "A small model that turns each card into a set of numbers, so the slip " +
                    "box can find notes that mean the same thing even when they share no " +
                    "words. Runs entirely on the phone. " +
                    "${EmbeddingModelStore.DEFAULT_SOURCE.label}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (status) {
                is EmbeddingModelStatus.NotDownloaded -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download (22 MB)")
                    }
                }
                is EmbeddingModelStatus.Downloading -> {
                    val total = status.totalBytes
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${status.downloadedBytes / 1_048_576} of ${total / 1_048_576} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is EmbeddingModelStatus.Ready -> {
                    Text(
                        "Installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSelfTest,
                        enabled = !selfTestRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selfTestRunning) "Testing…" else "Test that it works")
                    }
                    // Cards saved before the model was installed have no vector
                    // and are invisible to every search until this is run. Said
                    // as a count rather than hidden behind a spinner, because
                    // "why does it not find my old notes" is otherwise an
                    // unanswerable question.
                    if (pending > 0 || indexing) {
                        Text(
                            if (indexing) {
                                "Indexing… $pending to go."
                            } else {
                                "$pending card${if (pending == 1) "" else "s"} " +
                                    "saved before this model was installed. Until they are " +
                                    "indexed they cannot be found by meaning."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onIndexAll,
                            enabled = !indexing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (indexing) "Indexing…" else "Index them now")
                        }
                    } else {
                        Text(
                            "Every card is indexed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete the model")
                    }
                }
                is EmbeddingModelStatus.Failed -> {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Try the download again")
                    }
                }
            }

            if (selfTest.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // The verdict first, then the raw numbers under it. The numbers
                // are what makes the verdict checkable rather than something to
                // be taken on trust — and if this is ever reported as a bug,
                // they are the whole of the evidence.
                Text(selfTest.first(), style = MaterialTheme.typography.bodyMedium)
                selfTest.drop(1).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    cloudRescue: Boolean,
    onSetCloudRescue: (Boolean) -> Unit,
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
            CloudRescueToggle(enabled = cloudRescue, onChange = onSetCloudRescue)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ModelSourcePicker(modelUrl = modelUrl, onSetModelUrl = onSetModelUrl)
        }
    }
}

/**
 * How much text a capture hands the model.
 *
 * This is the one lever on card quality that has never been measured, and it
 * is exposed rather than guessed because guessing has a poor record here.
 *
 * The failure worth targeting is not bad writing, it is bad CHOOSING. A page
 * of a book holds four or five ideas; the prompt asks for "the one that
 * matters most"; and the on-device model reliably takes the most obvious event
 * rather than the argument being made about it. Selection is the hard half of
 * the task and the half a small model is worst at.
 *
 * One paragraph leaves nothing to choose between. The model only has to state
 * the idea in front of it, which is the half it can already do.
 *
 * Whether that is true is unknown. Three prompt rewrites were spent on this
 * problem and not one of them tried handing the model less to read, so the
 * number is a setting and the reader can settle it in three captures.
 */
@Composable
private fun CaptureSizeCard(captureChars: Int, onSelect: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("How much a card reads", style = MaterialTheme.typography.titleMedium)
            Text(
                "The text handed to the model when you capture. Less text means fewer " +
                    "ideas competing, which is what the offline model struggles to choose " +
                    "between. More text means more context and more to get lost in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CAPTURE_SIZES.forEach { (chars, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = captureChars == chars,
                            onClick = { onSelect(chars) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = captureChars == chars, onClick = { onSelect(chars) })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Capture the same passage at each size and compare. The capture log " +
                    "records which size produced which card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Deliberately three, not a slider. A slider invites fiddling; three sizes far
 * enough apart to tell apart invites a comparison, which is the point.
 */
private val CAPTURE_SIZES: List<Pair<Int, String>> = listOf(
    350 to "One paragraph — least to choose between",
    700 to "Two paragraphs",
    LumenCapture.PASSAGE_TARGET_CHARS to "A page (standard)",
)

/**
 * What happens when the on-device model cannot draft a card at all.
 *
 * Deliberately narrow, and the wording says so: this is not "use Gemini when
 * the card is weak". A card the offline model produced is kept whatever its
 * quality, because replacing it would spend the reader's quota on their behalf
 * — that is what the "Rewrite with Gemini" button on the card is for. This
 * fires only when there was no card: no model installed, too little free
 * memory to load it, or a reply that could not be used.
 */
@Composable
private fun CloudRescueToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Finish failed captures with Gemini", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "When the offline model can't draft a card, the passage goes to " +
                        "Gemini instead of falling back to a plain first-sentence card. " +
                        "Needs a Gemini key, and the card says when it happened."
                } else {
                    "Off — a capture the offline model can't do becomes a plain draft " +
                        "from the passage, and nothing leaves the phone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onChange)
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
        "A bigger model needs roughly 1.7x its file size in FREE memory to load — " +
            "so a 1.6 GB download wants about 2.7 GB free, which this phone does " +
            "not reliably have. Check the free figure in the capture log before " +
            "spending the download: under it, the model is refused and capture " +
            "falls back to the plain draft.",
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
    // Both addresses came from Hugging Face's own file listing rather than
    // from memory, which is the difference between these buttons and the one
    // that used to sit here promising a Qwen download that 404'd.
    OutlinedButton(
        onClick = { draft = LumenModelStore.ALT_Q4_EKV4096_URL },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(LumenModelStore.ALT_Q4_EKV4096_LABEL)
    }
    OutlinedButton(
        onClick = { draft = LumenModelStore.ALT_Q8_EKV4096_URL },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(LumenModelStore.ALT_Q8_EKV4096_LABEL)
    }
    Button(
        onClick = { onSetModelUrl(draft.trim().takeIf { it.isNotBlank() }) },
        enabled = draft.trim().isNotBlank() && draft.trim() != modelUrl,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Use this model")
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

/**
 * Permission to be interrupted about flashcards.
 *
 * OFF by default, deliberately. A wrong default here is not a preference the
 * reader shrugs at — an app that starts notifying someone who never asked is
 * one they uninstall, and asking costs a single tap.
 *
 * SINCE THEN THE DEFAULT HAS FLIPPED ON
 *
 * Not because that worry was wrong, but because it is answered somewhere else
 * now: the notification permission is requested in the reading chair, straight
 * after the reader answers their first question, and until it is granted the
 * app cannot interrupt anybody. Declining there switches this back off. So
 * this card is where the decision is REVISITED rather than where it is made,
 * and a reader who never grants the permission finds it already off.
 *
 * The copy explains the batching, because otherwise the feature looks broken:
 * a reader who turns this on, sees a card fall due, and hears nothing that
 * evening will conclude it does not work. It is working — it is waiting until
 * a sitting is worth having.
 */
@Composable
private fun ReviewRemindersCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // On API 33+ the permission has to be asked for, and a toggle that turns
    // on without it is the worst outcome available: the reader believes
    // reminders are on and simply never hears from the app again. So the
    // switch turns on only once the grant comes back.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onChange(granted) }
    val context = LocalContext.current

    fun request() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onChange(true)
            return
        }
        val already = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (already) onChange(true) else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Review reminders", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled) {
                        "You will hear from the app when a review sitting is worth " +
                            "having — not every time a single card falls due. It waits " +
                            "for a fuller sitting while waiting is cheap, and stops " +
                            "asking after six reminders you have not acted on."
                    } else {
                        "Off — cards still come due, and nothing will tell you. " +
                            "Spaced repetition you have to remember to open is a pile " +
                            "of cards."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { wanted -> if (wanted) request() else onChange(false) },
            )
        }
    }
}
