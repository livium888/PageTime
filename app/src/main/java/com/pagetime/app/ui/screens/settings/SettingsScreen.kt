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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import kotlin.math.roundToLong
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import com.pagetime.app.data.learning.CardDensity
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.embed.EmbeddingModelStatus
import com.pagetime.app.data.embed.EmbeddingModelStore
import com.pagetime.app.data.learning.ChapterPromptText
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.AiAnalysisLevel
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppSettingsRow
import com.pagetime.app.BuildConfig
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
    onManageExternalReadingApps: () -> Unit,
    onManageSiteRules: () -> Unit,
    onPermissions: () -> Unit,
    onUsageAudit: () -> Unit,
    onPrompts: () -> Unit,
    onScheduling: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val balanceSeconds by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsStateWithLifecycle()
    val ratio by viewModel.ratio.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val readInLastDay by viewModel.readInLastDay.collectAsStateWithLifecycle()
    val emergencyThisWeek by viewModel.emergencyThisWeek.collectAsStateWithLifecycle()
    val helpEnabled by viewModel.helpEnabled.collectAsStateWithLifecycle()
    val externalReadingEnabled by viewModel.externalReadingEnabled.collectAsStateWithLifecycle()
    val externalReadingDailyCapSeconds by viewModel.externalReadingDailyCapSeconds.collectAsStateWithLifecycle()

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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Read today", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            BlockScreenText.span(gate.target - gate.secondsToUnlock) +
                                " of " + BlockScreenText.span(gate.target),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { gate.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (gate.targetMet) {
                            "Done for today. Your phone is open until 4am."
                        } else {
                            "Read ${BlockScreenText.span(gate.secondsToUnlock)} more to unlock " +
                                "your phone until 4am."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Shown whichever rule is in force: what was actually read.
                // Not the same question as today's reading above — that one
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

                // The target is always visible, whether or not the lock is on,
                // so the reader can set their terms before switching it on.
                Text("Daily reading", style = MaterialTheme.typography.titleMedium)

                // The bounds are the lock's, not the screen's: before today's
                // reading is done the target may only go up. Typing is the one
                // instrument that holds that honestly — a slider can only refuse
                // by springing back, which reads as broken.
                val targetBounds =
                    GateState.targetBounds(gate.target, gate.canLoosenTheRules)
                TypedSettingField(
                    title = "Daily reading target",
                    help = "Minutes of reading each day, counted from 4am, before the rest " +
                        "of your phone opens.",
                    current = BlockScreenText.span(gate.target),
                    inputLabel = "Minutes of reading",
                    inputHint = "e.g. 20",
                    allowDecimal = false,
                    enabled = GateState.hasTravel(targetBounds),
                    enabledNote =
                        "Already at the ceiling — ${BlockScreenText.span(targetBounds.last)} " +
                            "a day. Lowering it waits until today's reading is done.",
                    minAllowed = targetBounds.first / 60.0,
                    maxAllowed = targetBounds.last / 60.0,
                    unit = "minutes",
                    fenceNote = if (gate.canLoosenTheRules) null else TARGET_FENCE_NOTE,
                    describe = { BlockScreenText.span((it * 60).roundToLong()) },
                    onCommit = { viewModel.setDailyTargetSeconds((it * 60).roundToLong()) },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // Reading inside PageTime is verified. This counts on trust — foreground time in
                // a chosen app looks the same whether a page is turning or the
                // phone is just sitting there — so it stays off unless asked
                // for, nothing is trusted until named on its own screen, and
                // the daily cap bounds what trusting it wrong can cost.
                ExternalReadingToggle(
                    enabled = externalReadingEnabled,
                    onChange = { viewModel.setExternalReadingEnabled(it) },
                )

                if (externalReadingEnabled) {
                    Spacer(Modifier.height(8.dp))
                    AppSettingsRow(
                        icon = Icons.Outlined.Block,
                        label = "Choose which apps (Kindle, etc.)",
                        onClick = onManageExternalReadingApps
                    )
                    Spacer(Modifier.height(8.dp))
                    TypedSettingField(
                        title = "Daily credit cap",
                        help = "The most these apps combined can bank per day, already at half rate. " +
                            "Set to 0 to stop them from earning anything without turning the toggle off.",
                        current = "$externalReadingDailyCapSeconds seconds/day",
                        inputLabel = "Seconds per day",
                        inputHint = "e.g. 3600",
                        allowDecimal = false,
                        minAllowed = 0.0,
                        maxAllowed = MAX_EXTERNAL_READING_DAILY_CAP_SECONDS,
                        unit = "seconds",
                        describe = { "${it.roundToLong()} sec/day" },
                        onCommit = { viewModel.setExternalReadingDailyCap(it.roundToLong()) },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily reading lock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Until you have read ${BlockScreenText.span(gate.target)} today, " +
                                "only your allowed apps open. Then everything opens until 4am. " +
                                "Turning this off takes 24 hours.",
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
                    Text(
                        "Switching off in " + BlockScreenText.span(gate.secondsUntilDisabled) +
                            ". Until then the lock still applies. Turn it back on any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (!gate.enabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Browse balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatMinutes(balanceSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TypedSettingField(
                        title = "Reading rate",
                        help = "1 minute of reading earns ${"%.1f".format(ratio)} minutes of " +
                            "browsing.",
                        current = "${"%.1f".format(ratio)} min of browsing per minute read",
                        inputLabel = "Minutes of browsing",
                        inputHint = "e.g. 1.5",
                        allowDecimal = true,
                        minAllowed = MIN_READING_RATE,
                        maxAllowed = MAX_READING_RATE,
                        unit = "min per min",
                        describe = { "×${"%.1f".format(it)}" },
                        onCommit = { viewModel.setRatio(it) },
                    )
                }
            }

            SectionHeader("Protection")
            AppSettingsRow(
                icon = Icons.Outlined.Block,
                label = "Manage blocked apps",
                onClick = onManageBlockedApps
            )
            // Its own row rather than a section inside blocked apps: the two
            // lists answer different questions, and a site rule holds whether
            // or not there is time on the clock.
            AppSettingsRow(
                icon = Icons.Outlined.Language,
                label = "Website rules",
                onClick = onManageSiteRules
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
            SectionHeader("Learning")
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

            SectionHeader("Notifications")
            ReviewRemindersCard(
                enabled = viewModel.reviewReminders.collectAsStateWithLifecycle().value,
                onChange = viewModel::setReviewReminders,
            )

            SectionHeader("Review scheduling")
            AppSettingsRow(
                icon = Icons.Outlined.Schedule,
                label = "Scheduling",
                subtitle = "Learning steps, intervals and retention",
                onClick = onScheduling
            )

            SectionHeader("Your AI app")
            AppSettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                label = "Prompts & search",
                subtitle = "The prompts you take to your AI app, and finding related notes",
                onClick = onPrompts
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
 * Lets the reader tailor the prompt PageTime hands to their own AI app when
 * they capture a card. The passage is still trimmed and the prompt still
 * measured, so a hand-written prompt can produce a poor card but cannot make
 * every capture too large to send.
 */
@Composable
internal fun CapturePromptCard(
    prompt: String,
    isCustom: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var draft by remember(prompt) { mutableStateOf(prompt) }
    var expanded by remember { mutableStateOf(false) }
    val problem = LumenAiPrompts.templateProblem(draft)
    val tokens = remember(draft) { LumenAiPrompts.worstCaseTokens(draft) }
    val budget = LlmTokenBudget.inputBudget(CAPTURE_REPLY_TOKENS)

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
                    "What your AI app is asked for when you capture a card. " +
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
                            "About $tokens tokens on a full page, over the $budget a capture " +
                                "prompt is kept to. Long captures will be trimmed."
                        else ->
                            "About $tokens tokens on a full page, of the $budget a capture prompt is kept to."
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

/**
 * Lets the reader say what a good flashcard is, rather than wait for a release
 * that guesses.
 *
 * WHAT THE INSTRUCTIONS COVER, AND WHAT THEY CANNOT
 *
 * Everything the model is told about the cards, and nothing about the shape of
 * its reply. The app appends that part in the same words whatever is written
 * here, because the fields it names are what the local checks use to reject a
 * card that is not grounded in the passage — a settings field that could
 * remove the check would be a field that can install an invented fact into a
 * review schedule. The help text says so instead of leaving the reader to find
 * out from an empty result.
 *
 * The how-many paragraph is nearly the same case: when it is not placed, the
 * app appends the shipped one, because it carries the switch from a ceiling to
 * a floor for a passage the reader hand-picked and losing that would make a
 * chosen passage produce nothing.
 */
@Composable
internal fun ChapterPromptCard(
    prompt: String,
    isCustom: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var draft by remember(prompt) { mutableStateOf(prompt) }
    var expanded by remember { mutableStateOf(false) }
    val problem = ChapterPromptText.templateProblem(draft)
    val placesCount = draft.contains(ChapterPromptText.HOW_MANY_TOKEN)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Flashcard prompt", style = MaterialTheme.typography.titleMedium)
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
                    "What your AI app is asked for when it writes cards from a stretch of a book or a PDF " +
                        "page's cards. ${ChapterPromptText.PASSAGES_TOKEN} is replaced " +
                        "with the chosen passages, numbered from zero; " +
                        "${ChapterPromptText.BOOK_TOKEN} with the book's title and " +
                        "${ChapterPromptText.CHAPTER_TOKEN} with the chapter's. " +
                        "${ChapterPromptText.HOW_MANY_TOKEN} is replaced with how many " +
                        "prompts to write per passage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    minLines = 10,
                    maxLines = 40,
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    problem ?: if (placesCount) {
                        "The reply's fields — passageIndex, prompt, answer, explanation, " +
                            "sourceQuote, type — are fixed and added by the app. A quote " +
                            "that is not in the passage still discards its card."
                    } else {
                        "You left out ${ChapterPromptText.HOW_MANY_TOKEN}, so the built-in " +
                            "count paragraph is added after your instructions. The reply's " +
                            "fields are fixed and added by the app."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (problem != null) {
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
                            draft = ChapterPromptText.DEFAULT_TEMPLATE
                        },
                        enabled = isCustom || draft != ChapterPromptText.DEFAULT_TEMPLATE
                    ) {
                        Text("Restore default")
                    }
                }
                Text(
                    "Used from the next generation on — a new chapter, or one you " +
                        "regenerate. Cards you have kept are yours and survive.",
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

/**
 * The retrieval model, and the button that proves it works.
 *
 * This is the only model left on the phone. It finds which cards are alike;
 * it writes nothing, and the reader's own AI app does all the writing.
 *
 * The self-test earns its place in the UI rather than living in a test suite.
 * Everything under it is already unit-tested, but only against fixtures: the
 * failures that survive to here — a vocabulary offset by a row, an export that
 * pools its own output — do not throw, and produce vectors of the right shape
 * whose neighbours are merely worse. Ten seconds on the reader's own phone is
 * the only place that can be caught.
 */
@Composable
internal fun EmbeddingModelSettingsCard(
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
internal fun CaptureSizeCard(captureChars: Int, onSelect: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("How much a card reads", style = MaterialTheme.typography.titleMedium)
            Text(
                "The text carried to your AI app when you capture. Less text means fewer " +
                    "ideas competing, which is what an AI app can struggle to choose " +
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
 * Credits time spent in a chosen app (Kindle, etc.) as reading, on trust.
 *
 * PageTime's own reader can tell a page turning from a phone merely left on
 * — it watches real scroll position. A foreign app's screen offers no such
 * signal; all this can ever see is "the app was in front with the screen
 * on". Off by default for that reason, and the description says so plainly
 * rather than dressing the toggle up as something it isn't. Which apps
 * qualify is a separate, explicit choice — see
 * [ExternalReadingAppsScreen][com.pagetime.app.ui.screens.settings.ExternalReadingAppsScreen] —
 * so turning this on alone trusts nothing yet.
 */
@Composable
private fun ExternalReadingToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Credit reading time in other apps", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "Time spent in an app you choose below, with the screen on, counts as " +
                        "reading (and toward today's target), at half rate, capped per day. This is trust-based — " +
                        "PageTime cannot tell a page turning from a phone left open, unlike its " +
                        "own reader."
                } else {
                    "Off — only reading inside PageTime counts, since it's the only reading this " +
                        "app can actually verify."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun AiAnalysisSettingsCard(
    level: AiAnalysisLevel,
    onSelect: (AiAnalysisLevel) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reading analysis", style = MaterialTheme.typography.titleMedium)
            Text(
                "While you read, PageTime builds a concept map of each chapter on your phone, from the text itself. Nothing is sent anywhere. This sets how often it looks.",
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

/**
 * A time setting the reader types instead of drags.
 *
 * These four were sliders, and a slider is the wrong instrument for a number
 * the gate fences. Its travel is fixed while the range it may take moves with
 * the gate; its steps hide the value (a twenty-three-step reward slider cannot
 * say 47 seconds, and a ratio slider's five positions cannot say 1.2); and the
 * one thing it always allows — a nudge in either direction — is the one thing
 * this screen sometimes has to refuse.
 *
 * Out of range is REFUSED, not clamped. A typed 5 minutes that silently became
 * the 15-minute floor would be the app deciding the reader's terms for them,
 * and nothing on the screen would have said it had.
 */
@Composable
private fun TypedSettingField(
    title: String,
    help: String,
    current: String,
    inputLabel: String,
    inputHint: String,
    allowDecimal: Boolean,
    minAllowed: Double,
    maxAllowed: Double,
    unit: String,
    describe: (Double) -> String,
    onCommit: (Double) -> Unit,
    enabled: Boolean = true,
    enabledNote: String? = null,
    fenceNote: String? = null,
) {
    var draft by rememberSaveable(title) { mutableStateOf("") }
    val typed = draft.trim().toDoubleOrNull()
    val inRange = typed != null && typed >= minAllowed && typed <= maxAllowed

    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(current, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        help,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!enabled) {
        // Nothing left to set in the allowed direction (e.g. a target already
        // at its ceiling). Said plainly rather than left as a dead control the
        // reader would keep tapping.
        Text(
            enabledNote.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                // Filtered as it is typed, so the error state can only mean a
                // real mistake — a stray minus, or a second decimal point.
                onValueChange = { text ->
                    draft = text.filter { char ->
                        char.isDigit() || (allowDecimal && char == '.')
                    }
                },
                label = { Text(inputLabel) },
                placeholder = { Text(inputHint) },
                singleLine = true,
                isError = draft.isNotBlank() && !inRange,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = {
                    typed?.let(onCommit)
                    // Cleared on commit so the field can never disagree with
                    // the value printed above it.
                    draft = ""
                },
                enabled = inRange
            ) {
                val label = if (typed != null && inRange) describe(typed) else ""
                Text(if (label.isEmpty()) "Set" else "Set $label")
            }
        }
        Text(
            "Allowed now: ${typedNumberLabel(minAllowed)} – " +
                "${typedNumberLabel(maxAllowed)} $unit.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val trimmed = draft.trim()
        val refusal = if (trimmed.isEmpty()) {
            null
        } else if (typed == null) {
            "Refused — \"$trimmed\" is not a number."
        } else if (typed < minAllowed || typed > maxAllowed) {
            "Refused — ${describe(typed)} is outside what is allowed right now."
        } else {
            null
        }
        if (refusal != null) {
            Text(
                refusal,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        fenceNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * The two fences, in the reader's words.
 *
 * The rule is the gate's, not the screen's: with no app time in hand the terms
 * may only be made stricter. Each field says what that means for itself,
 * because "you cannot do that" without a reason is how a fence starts to look
 * like a bug.
 */
private const val TARGET_FENCE_NOTE =
    "Right now the target can only go up — lowering it waits until today's reading is done."


/** The reward slider's old range, kept as the field's bounds. */
private const val MAX_EXTERNAL_READING_DAILY_CAP_SECONDS = 4.0 * 3_600.0

/** The reading-rate slider's old range, kept as the field's bounds. */
private const val MIN_READING_RATE = 0.5
private const val MAX_READING_RATE = 3.0

/** A typed number as it reads back: 90, or 1.5 when it is not whole. */
private fun typedNumberLabel(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

/**
 * How many cards to ask an AI for.
 *
 * Fewer is the better answer for most reading, so the default is the middle
 * option and the first one is "only the best". Whatever is chosen is a ceiling:
 * the prompt tells the AI to return fewer when fewer deserve a card.
 */
@Composable
internal fun CardDensityCard(density: CardDensity, onSelect: (CardDensity) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("How many cards", style = MaterialTheme.typography.titleMedium)
            Text(
                "The most cards your AI is asked to write from what you read. It is told " +
                    "to write fewer when fewer deserve a card, and a card you already have " +
                    "is left out when you paste the reply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardDensity.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = density == option,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = density == option, onClick = { onSelect(option) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Whether and how often the reader is offered cards while reading. See CardNudge. */
@Composable
internal fun CardNudgeCard(
    enabled: Boolean,
    every: Int,
    onEnabled: (Boolean) -> Unit,
    onEvery: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Suggest cards while reading", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "After you have read a while, a bar at the foot of the page offers to make " +
                            "cards. It waits for a new paragraph, heading or chapter, and goes away " +
                            "if you read on. Later keeps counting and asks again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            if (enabled) {
                com.pagetime.app.data.learning.CardNudge.EVERY_CHOICES.forEach { words ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = every == words,
                                onClick = { onEvery(words) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = every == words, onClick = { onEvery(words) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Every ${"%,d".format(java.util.Locale.US, words)} words " +
                                "(about ${words / 250} minutes)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Tokens a capture's reply may take; the rest of the budget belongs to the prompt. */
private const val CAPTURE_REPLY_TOKENS = 384
