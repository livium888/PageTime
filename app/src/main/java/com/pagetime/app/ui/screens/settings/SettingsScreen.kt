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
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
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

    val reviewReminders by viewModel.reviewReminders.collectAsStateWithLifecycle()
    val cardDensity by viewModel.cardDensity.collectAsStateWithLifecycle()
    val suggestCards by viewModel.cardNudgeEnabled.collectAsStateWithLifecycle()
    val suggestEvery by viewModel.cardNudgeEvery.collectAsStateWithLifecycle()
    val appsSummary by viewModel.appsSummary.collectAsStateWithLifecycle()
    val sitesSummary by viewModel.sitesSummary.collectAsStateWithLifecycle()
    val permissionsSummary = if (hasUsageAccessPermission(settingsContext) &&
        isAccessibilityServiceEnabled(settingsContext)
    ) "All granted" else "Needs attention"

    // ONE SAVE FOR THE WHOLE PAGE. See SettingsDraft.
    //
    // The draft is null until something is touched, so the page always starts
    // from what is stored, however late it loads.
    val saved = SettingsDraft(
        // "On" means staying on. While the 24-hour switch-off is running the
        // stored switch is still on, but showing it as on would offer "turn
        // off" for something already on its way off.
        lockOn = gate.enabled && !gate.windingDown,
        targetSeconds = gate.target,
        otherAppsCount = externalReadingEnabled,
        otherAppsCapSeconds = externalReadingDailyCapSeconds,
        readingRate = ratio,
        reviewReminders = reviewReminders,
        cardDensity = cardDensity,
        suggestCards = suggestCards,
        suggestEveryWords = suggestEvery,
        slipBoxHelp = helpEnabled,
    )
    var draft by remember { mutableStateOf<SettingsDraft?>(null) }
    val shown = draft ?: saved
    val changes = SettingsDraft.changes(saved, shown)
    val dirty = changes.isNotEmpty()
    fun edit(update: (SettingsDraft) -> SettingsDraft) { draft = update(shown) }

    var confirmSave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = dirty) { confirmSave = true }

    // Reminders need the notification permission on Android 13 and later, and a
    // switch that turns on without it is one the reader never hears from. So
    // turning them on is saved only once the grant comes back.
    val remindersPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setReviewReminders(granted) }

    fun applyChanges(from: SettingsDraft, to: SettingsDraft) {
        if (from.targetSeconds != to.targetSeconds) viewModel.setDailyTargetSeconds(to.targetSeconds)
        if (from.otherAppsCount != to.otherAppsCount) viewModel.setExternalReadingEnabled(to.otherAppsCount)
        if (from.otherAppsCapSeconds != to.otherAppsCapSeconds) viewModel.setExternalReadingDailyCap(to.otherAppsCapSeconds)
        if (from.readingRate != to.readingRate) viewModel.setRatio(to.readingRate)
        if (from.cardDensity != to.cardDensity) viewModel.setCardDensity(to.cardDensity)
        if (from.suggestCards != to.suggestCards) viewModel.setCardNudgeEnabled(to.suggestCards)
        if (from.suggestEveryWords != to.suggestEveryWords) viewModel.setCardNudgeEvery(to.suggestEveryWords)
        if (from.slipBoxHelp != to.slipBoxHelp) viewModel.setHelpEnabled(to.slipBoxHelp)
        if (from.reviewReminders != to.reviewReminders) {
            val needsAsking = to.reviewReminders &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(settingsContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsking) remindersPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else viewModel.setReviewReminders(to.reviewReminders)
        }
        // Last, so the target the lock starts with is the one just saved.
        if (from.lockOn != to.lockOn) viewModel.setGateSwitchedOn(to.lockOn)
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save these changes?") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    changes.forEach { change ->
                        Column {
                            Text(change.setting, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${change.from}  \u2192  ${change.to}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            change.consequence?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    applyChanges(saved, shown)
                    draft = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmSave = false
                        draft = null
                    }) { Text("Discard") }
                    TextButton(onClick = { confirmSave = false }) { Text("Keep editing") }
                }
            },
        )
    }

    val targetBounds = GateState.targetBounds(gate.target, gate.canLoosenTheRules)
    when (editing) {
        SettingsDraft.DAILY_TARGET -> NumberDialog(
            title = "Daily reading target",
            help = "Minutes of reading each day, counted from 4am, before the rest of your phone opens." +
                if (gate.canLoosenTheRules) "" else " Lowering it waits until today's reading is done.",
            unit = "minutes",
            initial = shown.targetSeconds / 60.0,
            min = targetBounds.first / 60.0,
            max = targetBounds.last / 60.0,
            decimal = false,
            onSet = { v -> edit { it.copy(targetSeconds = (v * 60).roundToLong()) } },
            onDismiss = { editing = null },
        )
        "cap" -> NumberDialog(
            title = "Other apps, most per day",
            help = "The most minutes a day that reading in other apps can count, already at half rate.",
            unit = "minutes",
            initial = shown.otherAppsCapSeconds / 60.0,
            min = 0.0,
            max = MAX_EXTERNAL_READING_DAILY_CAP_SECONDS / 60.0,
            decimal = false,
            onSet = { v -> edit { it.copy(otherAppsCapSeconds = (v * 60).roundToLong()) } },
            onDismiss = { editing = null },
        )
        "rate" -> NumberDialog(
            title = "Browsing earned per minute read",
            help = "One minute of reading earns this many minutes of browsing.",
            unit = "minutes",
            initial = shown.readingRate,
            min = MIN_READING_RATE,
            max = MAX_READING_RATE,
            decimal = true,
            onSet = { v -> edit { it.copy(readingRate = v) } },
            onDismiss = { editing = null },
        )
        "density" -> ChoiceDialog(
            title = "How many cards",
            options = CardDensity.entries.map { Triple(it.key, it.label, it.description) },
            selected = shown.cardDensity.key,
            onPick = { key -> edit { it.copy(cardDensity = CardDensity.fromKey(key)) } },
            onDismiss = { editing = null },
        )
        "every" -> ChoiceDialog(
            title = "Suggest cards",
            options = com.pagetime.app.data.learning.CardNudge.EVERY_CHOICES.map {
                Triple(it.toString(), SettingsDraft.words(it), "About ${it / 250} minutes of reading")
            },
            selected = shown.suggestEveryWords.toString(),
            onPick = { key -> edit { it.copy(suggestEveryWords = key.toIntOrNull() ?: it.suggestEveryWords) } },
            onDismiss = { editing = null },
        )
    }

    Scaffold(
        // A plain line, not an app bar: the bar spent a fifth of the screen on
        // one word, and this page is a list to be scanned.
        topBar = {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        },
        bottomBar = {
            if (dirty) {
                Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (changes.size == 1) "1 change not saved" else "${changes.size} changes not saved",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { draft = null }) { Text("Discard") }
                        Button(onClick = { confirmSave = true }) { Text("Save changes") }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // What happened, which nothing on this page changes: read-only.
            SectionHeader("Today")
            SettingsGroup {
                if (gate.enabled) {
                    SettingsLine(
                        label = "Read today",
                        value = "${(gate.target - gate.secondsToUnlock) / 60} of ${SettingsDraft.minutes(gate.target)}",
                    )
                } else {
                    SettingsLine(label = "Browsing balance", value = SettingsDraft.minutes(balanceSeconds))
                }
                SettingsLine(label = "Last 24 hours", value = SettingsDraft.minutes(readInLastDay))
                SettingsLine(label = "All time", value = SettingsDraft.minutes(totalReadingSeconds))
                if (emergencyThisWeek > 0) {
                    SettingsLine(label = "Emergency unlocks this week", value = "$emergencyThisWeek")
                }
            }

            SectionHeader("Reading lock")
            SettingsGroup {
                SettingsLine(
                    label = SettingsDraft.DAILY_LOCK,
                    sub = if (gate.windingDown && shown.lockOn == saved.lockOn) {
                        "Switching off in ${BlockScreenText.span(gate.secondsUntilDisabled)}. Still applies until then."
                    } else null,
                    was = SettingsDraft.onOff(saved.lockOn).takeIf { saved.lockOn != shown.lockOn },
                    checked = shown.lockOn,
                    onToggle = { on -> edit { it.copy(lockOn = on) } },
                )
                SettingsLine(
                    label = SettingsDraft.DAILY_TARGET,
                    value = SettingsDraft.minutes(shown.targetSeconds),
                    was = SettingsDraft.minutes(saved.targetSeconds).takeIf { saved.targetSeconds != shown.targetSeconds },
                    onClick = { editing = SettingsDraft.DAILY_TARGET },
                )
                if (!gate.enabled) {
                    SettingsLine(
                        label = "Browsing earned per minute read",
                        value = SettingsDraft.rate(shown.readingRate),
                        was = SettingsDraft.rate(saved.readingRate).takeIf { saved.readingRate != shown.readingRate },
                        onClick = { editing = "rate" },
                    )
                }
                SettingsLine(
                    label = "Apps",
                    value = appsSummary,
                    onClick = onManageBlockedApps,
                )
                SettingsLine(
                    label = "Websites",
                    value = sitesSummary,
                    onClick = onManageSiteRules,
                )
            }

            SectionHeader("Reading in other apps")
            SettingsGroup {
                SettingsLine(
                    label = "Count reading in other apps",
                    was = SettingsDraft.onOff(saved.otherAppsCount).takeIf { saved.otherAppsCount != shown.otherAppsCount },
                    checked = shown.otherAppsCount,
                    onToggle = { on -> edit { it.copy(otherAppsCount = on) } },
                )
                if (shown.otherAppsCount) {
                    SettingsLine(
                        label = "Most per day",
                        value = SettingsDraft.minutes(shown.otherAppsCapSeconds),
                        was = SettingsDraft.minutes(saved.otherAppsCapSeconds)
                            .takeIf { saved.otherAppsCapSeconds != shown.otherAppsCapSeconds },
                        onClick = { editing = "cap" },
                    )
                    SettingsLine(label = "Which apps", onClick = onManageExternalReadingApps)
                }
            }

            SectionHeader("Cards and review")
            SettingsGroup {
                SettingsLine(
                    label = "Review reminders",
                    was = SettingsDraft.onOff(saved.reviewReminders).takeIf { saved.reviewReminders != shown.reviewReminders },
                    checked = shown.reviewReminders,
                    onToggle = { on -> edit { it.copy(reviewReminders = on) } },
                )
                SettingsLine(
                    label = "How many cards",
                    value = shown.cardDensity.label,
                    was = saved.cardDensity.label.takeIf { saved.cardDensity != shown.cardDensity },
                    onClick = { editing = "density" },
                )
                SettingsLine(
                    label = "Suggest cards while reading",
                    was = SettingsDraft.onOff(saved.suggestCards).takeIf { saved.suggestCards != shown.suggestCards },
                    checked = shown.suggestCards,
                    onToggle = { on -> edit { it.copy(suggestCards = on) } },
                )
                if (shown.suggestCards) {
                    SettingsLine(
                        label = "How often",
                        value = SettingsDraft.words(shown.suggestEveryWords),
                        was = SettingsDraft.words(saved.suggestEveryWords)
                            .takeIf { saved.suggestEveryWords != shown.suggestEveryWords },
                        onClick = { editing = "every" },
                    )
                }
                SettingsLine(label = "Review scheduling", onClick = onScheduling)
                SettingsLine(label = "Prompts for your AI app", onClick = onPrompts)
            }

            SectionHeader("Setup and help")
            SettingsGroup {
                SettingsLine(label = "Permissions", value = permissionsSummary, onClick = onPermissions)
                SettingsLine(label = "Usage history", onClick = onUsageAudit)
                SettingsLine(
                    label = "Explain slip-box actions",
                    was = SettingsDraft.onOff(saved.slipBoxHelp).takeIf { saved.slipBoxHelp != shown.slipBoxHelp },
                    checked = shown.slipBoxHelp,
                    onToggle = { on -> edit { it.copy(slipBoxHelp = on) } },
                )
            }

            SectionHeader("About")
            AppVersionCard()
            CrashDiagnosticsCard(crashLogText = crashLogText)
        }
    }
}

/** One group of rows on the Settings page, drawn as a single surface with dividers between rows. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Column(content = content) }
}

/**
 * One row of the Settings page: a name on the left and its value on the right.
 *
 * Three shapes from the same row, so every setting lines up: read-only (a
 * value and nothing to tap), a value that opens an editor ([onClick]), and a
 * switch ([checked] with [onToggle]). An edited row says what it [was] in the
 * accent colour, so the reader can see what they have touched before saving.
 */
@Composable
private fun SettingsLine(
    label: String,
    value: String? = null,
    sub: String? = null,
    was: String? = null,
    checked: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tap: (() -> Unit)? = onClick ?: if (checked != null && onToggle != null) ({ onToggle(!checked) }) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier)
            // Every row the same height, switch or not, so the list reads as
            // one column of names and one column of values.
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            was?.let {
                Text("was $it · not saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            checked != null && onToggle != null ->
                // The stock switch is taller than a row of text; drawn smaller
                // it sits in the row instead of setting the row's height.
                Switch(
                    checked = checked,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(0.72f).height(28.dp),
                )
            else -> {
                value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        color = if (was != null) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onClick != null) {
                    Text(" \u203a", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** Types a number for the draft. Refuses what is outside [min]..[max] and says what the range is. */
@Composable
private fun NumberDialog(
    title: String,
    help: String,
    unit: String,
    initial: Double,
    min: Double,
    max: Double,
    decimal: Boolean,
    onSet: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    fun show(v: Double) = if (decimal) "%.1f".format(java.util.Locale.US, v) else v.roundToLong().toString()
    var text by remember { mutableStateOf(show(initial)) }
    val typed = text.toDoubleOrNull()
    val valid = typed != null && typed >= min && typed <= max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(help, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() || (decimal && it == '.') } },
                    label = { Text(unit.replaceFirstChar { it.uppercase() }) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
                    ),
                )
                Text(
                    "Between ${show(min)} and ${show(max)} $unit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    typed?.let(onSet)
                    onDismiss()
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks one of a few named options for the draft. Each option is (key, name, what it means). */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Triple<String, String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, name, meaning) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = key == selected,
                                onClick = {
                                    onPick(key)
                                    onDismiss()
                                },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == selected, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                meaning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
