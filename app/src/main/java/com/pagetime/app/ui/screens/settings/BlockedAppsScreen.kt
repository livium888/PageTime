package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.pagetime.app.blocker.AppAllowlist
import com.pagetime.app.blocker.AppMode
import com.pagetime.app.blocker.AppRules
import com.pagetime.app.blocker.AppRulesDraft
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedAppsScreen(
    onBack: () -> Unit,
    viewModel: BlockedAppsViewModel = viewModel()
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val blockedPackages by viewModel.blockedPackages.collectAsStateWithLifecycle()
    val hardLockUntil by viewModel.hardLockUntil.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val blockedStats by viewModel.blockedStats.collectAsStateWithLifecycle()
    val appMode by viewModel.appMode.collectAsStateWithLifecycle()
    val allowedApps by viewModel.allowedApps.collectAsStateWithLifecycle()
    val setupGraceUntil by viewModel.appAllowlistSetupGraceUntil.collectAsStateWithLifecycle()
    val essentials by viewModel.essentials.collectAsStateWithLifecycle()
    val refusal by viewModel.refusal.collectAsStateWithLifecycle()

    // A lightweight wall-clock ticker so the countdowns stay live and the switches
    // unlock the moment the hard lock expires — without any persisted-state churn.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val hardRemaining = (hardLockUntil - now).coerceAtLeast(0L)
    val hardLockActive = hardRemaining > 0

    // The rule that makes the lock mean anything. Without it today's reading
    // and "Settings, uncheck, done" are the same thing, and the second one is
    // quicker.
    //
    // Adding is never restricted — more blocking is not an escape, and making
    // someone earn the right to block something would be perverse — so the
    // freeze is asymmetric and applies only to switching an app OFF.
    val canUnblock = gate.canRemoveBlockedApps && !hardLockActive
    val inSetupWindow = AppAllowlist.inSetupWindow(setupGraceUntil, now)

    // NOTHING ON THIS SCREEN TAKES EFFECT UNTIL "SAVE CHANGES". See AppRulesDraft.
    //
    // The draft is null until the reader touches something, so the screen
    // always starts from the saved rules however late they load.
    val saved = AppRules(appMode, blockedPackages, allowedApps)
    var draft by remember { mutableStateOf<AppRules?>(null) }
    val shown = draft ?: saved
    val change = AppRulesDraft.diff(saved, shown)
    val dirty = !change.isEmpty
    fun edit(update: (AppRules) -> AppRules) { draft = update(shown) }

    var confirmSave by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val labels = remember(installed) { installed.associate { it.packageName to it.label } }
    val leave: () -> Unit = { if (dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = dirty) { confirmLeave = true }

    val allowlist = shown.mode == AppMode.ALLOWLIST
    // Leaving the allowlist that is in force is loosening; going back to the
    // blocklist in a draft that started there is just changing one's mind.
    val canChooseBlocklist = saved.mode == AppMode.BLOCKLIST ||
        (!hardLockActive && AppAllowlist.canLeave(inSetupWindow, gate.canSwitchToBlocklist))
    val roomToAllow = shown.allowed.size < AppAllowlist.MAX_APPS
    val canAddAllowed = !hardLockActive && AppAllowlist.canAdd(
        mode = saved.mode,
        allowedCount = shown.allowed.size,
        inSetupWindow = inSetupWindow,
        canLoosen = gate.canAddAllowedApp,
    )
    // Chosen apps first, so they are visible without scrolling; then the rest
    // alphabetically, as loaded. Ordered by the SAVED choice so a row does not
    // jump away under the finger that just switched it.
    val listed = if (allowlist) {
        installed.sortedByDescending { it.packageName in saved.allowed }
    } else {
        installed
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save these changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppRulesDraft.summary(change, shown, { labels[it] ?: it }).forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    viewModel.save(saved, shown)
                    draft = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Go back") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your changes on this screen have not been saved. Nothing has been blocked or allowed yet.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    draft = null
                    onBack()
                }) { Text("Discard changes") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (allowlist) "Allowed apps" else "Blocked apps") },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (dirty) {
                Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (change.count == 1) "1 change not saved" else "${change.count} changes not saved",
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
        // Everything — intro, override controls, and the app list — lives inside one
        // scrolling column so the app list uses the whole screen height and scrolls
        // away together with the header instead of being pinned to a sliver.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                AppModeChooser(
                    mode = shown.mode,
                    canChooseBlocklist = canChooseBlocklist,
                    onChoose = { mode -> edit { it.copy(mode = mode) } },
                )
            }
            if (saved.mode != shown.mode) {
                item {
                    Text(
                        if (allowlist) {
                            "You are only looking. Nothing is blocked until you press Save changes. " +
                                "Choose the apps you need first."
                        } else {
                            "Not switched yet. Press Save changes to go back to blocking only chosen apps."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    )
                }
            }
            item {
                Text(
                    when {
                        allowlist ->
                            "In this mode everything is blocked until today's reading is done, except calls, " +
                                "messages, your home screen, Settings, the clock, your keyboard, " +
                                "authenticator apps, and up to ${AppAllowlist.MAX_APPS} apps you choose."
                        gate.enabled ->
                            "Until today's reading is done, opening one of these sends you back to " +
                                "the reader. Apps can be added any time, but only removed once " +
                                "today's reading is done."
                        else ->
                            "While your balance is empty, opening one of these apps sends you back to the reader."
                    } +
                        " Sites, by address in every browser, are on the " +
                        "Website rules screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                )
            }
            if (allowlist) {
                item {
                    AllowlistStatus(
                        chosen = shown.allowed.size,
                        // Before the switch is saved the list is only being
                        // prepared, and choosing for it is free.
                        preparing = saved.mode != AppMode.ALLOWLIST,
                        inSetupWindow = inSetupWindow,
                        setupRemainingMillis = (setupGraceUntil - now).coerceAtLeast(0L),
                        canAdd = canAddAllowed,
                    )
                }
            }
            refusal?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            item {
                BlockOverrideControls(
                    hardLockActive = hardLockActive,
                    hardRemaining = hardRemaining,
                    onHardLock = viewModel::hardLock
                )
            }
            item {
                BlockingStatsCard(stats = blockedStats)
            }
            items(listed, key = { it.packageName }) { app ->
                val pkg = app.packageName
                if (allowlist) {
                    val allowed = pkg in shown.allowed
                    AllowlistRow(
                        label = app.label,
                        essential = AppAllowlist.isEssential(pkg, essentials),
                        allowed = allowed,
                        // Putting back an app that was allowed before this
                        // edit began only undoes the edit.
                        canAdd = canAddAllowed || (roomToAllow && pkg in saved.allowed && !hardLockActive),
                        onChange = { on ->
                            edit { it.copy(allowed = if (on) it.allowed + pkg else it.allowed - pkg) }
                        },
                    )
                    HorizontalDivider()
                } else {
                    val blocked = pkg in shown.blocked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            app.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Switch(
                            checked = blocked,
                            onCheckedChange = { on ->
                                edit { it.copy(blocked = if (on) it.blocked + pkg else it.blocked - pkg) }
                            },
                            // Blocking something is always allowed. Unblocking
                            // an app whose block is already saved waits for
                            // today's reading; one switched on in this edit and
                            // not yet saved can be switched straight off again.
                            // A hard lock refuses every change while it runs.
                            enabled = when {
                                hardLockActive -> false
                                blocked && pkg in saved.blocked -> canUnblock
                                else -> true
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BlockOverrideControls(
    hardLockActive: Boolean,
    hardRemaining: Long,
    onHardLock: (Long) -> Unit
) {
    var manualHours by remember { mutableStateOf("") }
    val manualMinutes = manualHours.toDoubleOrNull()?.times(60)?.roundToInt()?.coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // "Quick disable" — a five or ten minute pause on the block —
            // used to sit above this. It was deleted rather than hidden. Under
            // the session gate a two-hour boundary with a five-minute bypass
            // button beside it is not a boundary, it is a button, and leaving
            // it in place for the browse balance would have meant maintaining
            // two escape routes with opposite intentions.
            Text("Hard lock", style = MaterialTheme.typography.titleMedium)
            Text(
                "Commit to the block for a fixed time. Once locked it cannot be cancelled or\n" +
                    "lifted — not by today's reading, not by these toggles — no matter what.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onHardLock(30) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("30 min")
                }
                FilledTonalButton(
                    onClick = { onHardLock(60) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1 hr")
                }
                FilledTonalButton(
                    onClick = { onHardLock(120) },
                    enabled = !hardLockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2 hr")
                }
            }
            if (hardLockActive) {
                Text(
                    "Locked — ${formatRemaining(hardRemaining)} remaining. The block\n" +
                        "cannot be lifted until this runs out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                HorizontalDivider()

                Text(
                    "Custom duration",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualHours,
                        onValueChange = { manualHours = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Hours") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = !hardLockActive,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { manualMinutes?.let { onHardLock(it.toLong()) } },
                        enabled = !hardLockActive && manualMinutes != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (manualMinutes != null && manualMinutes >= 60) {
                            "Lock ${manualMinutes / 60.0} hr"
                        } else {
                            "Lock ${manualMinutes ?: 0} min"
                        })
                    }
                }
                Text(
                    "Set any hard-lock length in hours (e.g. 1, 1.5, or 3). It still cannot be cancelled early.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Block chosen apps, or allow only chosen apps.
 *
 * Entering the allowlist is always possible (it only narrows what opens) and
 * starts a free setup window. Leaving it reopens everything it did not name,
 * so that chip is disabled until today's reading is done.
 */
@Composable
private fun AppModeChooser(
    mode: AppMode,
    canChooseBlocklist: Boolean,
    onChoose: (AppMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = mode == AppMode.BLOCKLIST,
            onClick = { onChoose(AppMode.BLOCKLIST) },
            enabled = mode == AppMode.BLOCKLIST || canChooseBlocklist,
            label = { Text("Block chosen apps") }
        )
        FilterChip(
            selected = mode == AppMode.ALLOWLIST,
            onClick = { onChoose(AppMode.ALLOWLIST) },
            label = { Text("Allow only chosen apps") }
        )
    }
}

/** "3 of 5 chosen", and whether another can be added right now and why not. */
@Composable
private fun AllowlistStatus(
    chosen: Int,
    preparing: Boolean,
    inSetupWindow: Boolean,
    setupRemainingMillis: Long,
    canAdd: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "$chosen of ${AppAllowlist.MAX_APPS} chosen",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                when {
                    chosen >= AppAllowlist.MAX_APPS ->
                        "That's the limit. Remove one to choose another."
                    preparing ->
                        "Choose the apps you need. They only take effect when you save."
                    inSetupWindow ->
                        "Free setup: ${formatRemaining(setupRemainingMillis)} left to choose your apps."
                    canAdd ->
                        "You can add an app now, because today's reading is done."
                    else ->
                        "Adding an app waits until today's reading is done. Removing one is always allowed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One app in allowlist mode. Essentials are shown but cannot be switched off,
 * and never count against the five.
 */
@Composable
private fun AllowlistRow(
    label: String,
    essential: Boolean,
    allowed: Boolean,
    canAdd: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (essential) {
                Text(
                    "Always allowed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = essential || allowed,
            onCheckedChange = onChange,
            // Removing is the strict direction and always allowed; adding needs
            // room and either the setup window or today's reading.
            enabled = !essential && (allowed || canAdd)
        )
    }
}

/**
 * Last 24 hours of blocking, surfaced as a report card: how many times each
 * app was bounced and how much browse time it burned. The ledger already
 * records every BLOCKED and SPENT row; this just reads it back.
 */
@Composable
private fun BlockingStatsCard(stats: List<BlockedAppsViewModel.BlockStat>) {
    if (stats.isEmpty()) return
    val totalBlocked = stats.sumOf { it.blockedCount }
    val totalSpent = stats.sumOf { it.spentSeconds }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Blocking stats — last 24h", style = MaterialTheme.typography.titleMedium)
            // "these apps" was accurate until blocked sites started reporting
            // into the same ledger, and a count that silently includes a second
            // kind of thing is the sort of number this screen exists to avoid.
            // The per-row labels already say which is which.
            Text(
                "$totalBlocked time${if (totalBlocked == 1L) "" else "s"} blocked · " +
                    "${formatMinutes(totalSpent)} of browse time burned",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            stats.take(8).forEach { stat ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stat.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${stat.blockedCount}× · ${formatMinutes(stat.spentSeconds)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (stats.size > 8) {
                Text(
                    "+ ${stats.size - 8} more apps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatMinutes(seconds: Long): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return when {
        minutes <= 0 -> "${remainder}s"
        remainder == 0L -> "${minutes}m"
        else -> "${minutes}m ${remainder}s"
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes} min"
    } else {
        "${seconds}s"
    }
}
