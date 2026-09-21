package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.blocker.BlockScreenText
import com.pagetime.app.blocker.SiteMode
import com.pagetime.app.data.local.AllowedSiteEntity
import com.pagetime.app.data.local.BlockedSiteEntity
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppPrimaryButton
import kotlinx.coroutines.delay

/**
 * Sites off limits, or sites let through — by address, in every browser.
 *
 * ONE SCREEN, NOT TWO, AND NOT PER-RULE
 *
 * [SiteMode] is a single reader-wide switch, not a flag on each rule: a
 * reader is either drawing a line through specific sites or naming the only
 * ones they may reach, never both at once. One screen that shows whichever
 * list is active says that plainly; two screens (or a mode column on one
 * shared list) would suggest a mixing that was deliberately ruled out — see
 * [com.pagetime.app.blocker.SiteRules.blockingRule].
 *
 * BOTH LISTS ALWAYS EXIST
 *
 * Switching modes never deletes anything. A reader who built a careful
 * blocklist and tries allowlist mode for a week gets it back exactly as
 * they left it if they switch back — see [SiteRulesViewModel].
 *
 * THE FENCE ON ADDING TO AN ALLOWLIST HAS TWO PHASES
 *
 * A blocklist starts empty and unrestricted — adding to it is always free,
 * and only removing a rule (which reopens something) costs, via
 * [com.pagetime.app.domain.GateState.canLoosenTheRules]. An allowlist does
 * NOT start from the same safe place: empty means every site is off
 * limits, full stop. Fencing "add" from the first second the same way
 * "remove" is fenced on a blocklist traps a reader the instant they
 * switch modes, with nothing on the list yet and no browser access left
 * to earn a session with (that shipped once; a real reader hit it
 * immediately). Never fencing it at all is just as real a hole the other
 * way: add whatever site you want, right when you want it, for free,
 * forever, and the allowlist stops meaning anything.
 *
 * [SiteMode.ALLOWLIST_SETUP_GRACE_MILLIS] is the answer to both: a
 * one-time window, opened the moment a reader switches into allowlist mode,
 * where adding is free — long enough to build a real starter list without
 * having read a word. Once it closes, adding a site costs exactly what
 * unblocking an app costs
 * ([com.pagetime.app.domain.GateState.canAddAllowedSite]), same as
 * removing a block rule always has. Removing an allow rule stays free
 * forever either way — it only ever narrows. Switching an active allowlist
 * back to a blocklist costs a session too, since that's the one action
 * that reopens everything at once. A hard lock blocks adding to the
 * allowlist regardless of the window, since that's a live escape from an
 * active commitment, unlike plain list maintenance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteRulesScreen(
    onBack: () -> Unit,
    viewModel: SiteRulesViewModel = viewModel()
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val blockedSites by viewModel.blockedSites.collectAsStateWithLifecycle()
    val allowedSites by viewModel.allowedSites.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val hardLockUntil by viewModel.hardLockUntil.collectAsStateWithLifecycle()
    val allowlistSetupGraceUntil by viewModel.allowlistSetupGraceUntil.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val refused by viewModel.refused.collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val hardLockActive = hardLockUntil > now
    val setupGraceRemainingMillis = allowlistSetupGraceUntil - now
    val withinSetupGrace = setupGraceRemainingMillis > 0

    // The direction that widens what is reachable, whichever list is active.
    val canRemoveBlocked = gate.canRemoveBlockedApps && !hardLockActive
    // Free during the one-time setup window; the earned-session rule takes
    // back over once it closes. A hard lock blocks adding either way.
    val canAddAllowed = !hardLockActive && (withinSetupGrace || gate.canAddAllowedSite)
    val canSwitchToBlocklist = gate.canSwitchToBlocklist && !hardLockActive

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Website rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                ModeSelector(
                    mode = mode,
                    canSwitchToBlocklist = canSwitchToBlocklist,
                    onSelect = viewModel::setMode,
                )
            }

            item {
                Text(
                    introText(mode, gate.enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                AddSiteCard(
                    draft = draft,
                    message = message,
                    refused = refused,
                    enabled = mode == SiteMode.BLOCKLIST || canAddAllowed,
                    onEdit = viewModel::edit,
                    onAdd = viewModel::add
                )
            }

            when (mode) {
                SiteMode.BLOCKLIST -> blockedListItems(
                    sites = blockedSites,
                    canRemove = canRemoveBlocked,
                    hardLockActive = hardLockActive,
                    onRemove = viewModel::removeBlocked,
                )
                SiteMode.ALLOWLIST -> allowedListItems(
                    sites = allowedSites,
                    canAdd = canAddAllowed,
                    hardLockActive = hardLockActive,
                    withinSetupGrace = withinSetupGrace,
                    setupGraceRemainingMillis = setupGraceRemainingMillis,
                    onRemove = viewModel::removeAllowed,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: SiteMode,
    canSwitchToBlocklist: Boolean,
    onSelect: (SiteMode) -> Unit,
) {
    Column {
        ModeOption(
            label = "Block specific sites",
            selected = mode == SiteMode.BLOCKLIST,
            enabled = mode == SiteMode.BLOCKLIST || canSwitchToBlocklist,
            onSelect = { onSelect(SiteMode.BLOCKLIST) },
        )
        ModeOption(
            label = "Allow only these sites",
            selected = mode == SiteMode.ALLOWLIST,
            enabled = true,
            onSelect = { onSelect(SiteMode.ALLOWLIST) },
        )
        if (mode == SiteMode.ALLOWLIST && !canSwitchToBlocklist) {
            Text(
                "Switching back to a blocklist reopens everything you haven't explicitly " +
                    "blocked, so it costs what removing a rule costs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun introText(mode: SiteMode, gateEnabled: Boolean): String = when (mode) {
    SiteMode.BLOCKLIST -> if (gateEnabled) {
        "A site here is off limits in every browser — unless a session bought with " +
            "reading is open, the same as it opens a blocked app. Block a whole site with " +
            "bbc.co.uk, or one section of it with bbc.co.uk/news."
    } else {
        "A site here is off limits in every browser, whether or not you have time to " +
            "spend. Block a whole site with bbc.co.uk, or one section of it with " +
            "bbc.co.uk/news."
    }
    SiteMode.ALLOWLIST -> if (gateEnabled) {
        "Only sites listed here are reachable in any browser — everything else is off " +
            "limits, unless a session bought with reading is open. Allow a whole site " +
            "with bbc.co.uk, or one section of it with bbc.co.uk/news."
    } else {
        "Only sites listed here are reachable in any browser — everything else is off " +
            "limits, whether or not you have time to spend."
    }
}

private fun LazyListScope.blockedListItems(
    sites: List<BlockedSiteEntity>,
    canRemove: Boolean,
    hardLockActive: Boolean,
    onRemove: (BlockedSiteEntity) -> Unit,
) {
    if (sites.isEmpty()) {
        item {
            Text(
                "No sites yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    items(sites, key = { it.id }) { site ->
        SiteRow(
            id = site.id,
            wholeSite = site.pathPrefix == null,
            removable = canRemove,
            onRemove = { onRemove(site) },
        )
    }
    if (!canRemove) {
        item {
            Text(
                if (hardLockActive) {
                    "A hard lock is running, so nothing can be taken off this list until it ends."
                } else {
                    "Sites can be added any time, but only removed during a session. " +
                        "Removing one is how you get back into it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun LazyListScope.allowedListItems(
    sites: List<AllowedSiteEntity>,
    canAdd: Boolean,
    hardLockActive: Boolean,
    withinSetupGrace: Boolean,
    setupGraceRemainingMillis: Long,
    onRemove: (AllowedSiteEntity) -> Unit,
) {
    if (sites.isEmpty()) {
        item {
            Text(
                "Nothing allowed yet — every site is off limits until you add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        items(sites, key = { it.id }) { site ->
            // Removing an allow rule only ever narrows what is reachable, the
            // strict direction, so it is never fenced — not even by a hard
            // lock, which exists to stop loosening, not to stop this.
            SiteRow(
                id = site.id,
                wholeSite = site.pathPrefix == null,
                removable = true,
                onRemove = { onRemove(site) },
            )
        }
    }
    // Told plainly, both while it's running and the moment it's gone — a
    // window that closes silently just relocates the surprise from "why is
    // everything stuck" to "why did adding suddenly stop working".
    val notice = when {
        withinSetupGrace ->
            "Free setup window: ${BlockScreenText.span(setupGraceRemainingMillis / 1000)} " +
                "left to add sites without a session."
        hardLockActive ->
            "A hard lock is running, so nothing can be added to this list until it ends."
        !canAdd ->
            "The free setup window has ended. Adding a new site now costs what " +
                "removing a blocked site costs — read to bank a session."
        else -> null
    }
    if (notice != null) {
        item {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SiteRow(
    id: String,
    wholeSite: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                id,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (wholeSite) "Whole site, including subdomains" else "This section and everything under it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove, enabled = removable) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove $id")
        }
    }
    HorizontalDivider()
}

@Composable
private fun AddSiteCard(
    draft: String,
    message: String?,
    refused: Boolean,
    enabled: Boolean,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit
) {
    AppCard(modifier = Modifier.padding(top = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onEdit,
                label = { Text("Site") },
                placeholder = { Text("bbc.co.uk or bbc.co.uk/news") },
                singleLine = true,
                isError = refused,
                // Typing is never disabled — only submitting is fenced, the
                // same way a blank field disables the button rather than the
                // field itself, just below.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            AppPrimaryButton(
                text = "Add site",
                onClick = onAdd,
                // Blank is not an error worth a message; it is just nothing to
                // do. The refusal above stays reachable for input that looks
                // like an attempt.
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refused) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
