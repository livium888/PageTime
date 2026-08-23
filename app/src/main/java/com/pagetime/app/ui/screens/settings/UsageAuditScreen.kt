package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.UsageRepository
import com.pagetime.app.data.local.UsageEventEntity
import com.pagetime.app.ui.formatClock
import com.pagetime.app.ui.formatMinutes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageAuditScreen(
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: UsageAuditViewModel = viewModel()
) {
    val balance by viewModel.balanceSeconds.collectAsStateWithLifecycle()
    val earned by viewModel.earnedToday.collectAsStateWithLifecycle()
    val liveSpent by viewModel.liveSpentToday.collectAsStateWithLifecycle()
    val reconciled by viewModel.reconciledToday.collectAsStateWithLifecycle()
    val blocked by viewModel.blockedToday.collectAsStateWithLifecycle()
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    val status by viewModel.protectionStatus.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshProtectionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::reconcileNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reconcile usage now")
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Balance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatClock(balance),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "This is the persisted amount available to blocked apps. It survives closing PageTime and is changed through one serialized balance ledger.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    AuditRow("Earned from reading", formatMinutes(earned))
                    AuditRow("Spent while service was live", formatMinutes(liveSpent))
                    AuditRow("Recovered after PageTime was stopped", formatMinutes(reconciled))
                    AuditRow("Blocked at zero", blocked.toString())
                    Text(
                        "Recovered time comes from Android Usage access. It is the protection against time being free while PageTime was force-stopped or its service was unavailable.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            ProtectionCard(status = status, onPermissions = onPermissions)

            Text("Recent events", style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) {
                Text(
                    "No usage events recorded yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                events.forEach { event -> UsageEventRow(event) }
            }
        }
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ProtectionCard(status: ProtectionStatus, onPermissions: () -> Unit) {
    val allReady = status.accessibilityEnabled && status.overlayEnabled &&
        status.usageAccessEnabled && status.serviceConnected
    val lastReconcileAt = status.lastReconcileAt
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Protection status", style = MaterialTheme.typography.titleMedium)
            }
            StatusLine("Accessibility permission", status.accessibilityEnabled)
            StatusLine("Accessibility service connected", status.serviceConnected)
            StatusLine("Overlay permission", status.overlayEnabled)
            StatusLine("Usage access", status.usageAccessEnabled)
            Text(
                if (allReady) {
                    "Protection is active. Usage reconciliation runs after returning to PageTime and when the service reconnects."
                } else {
                    "Protection is incomplete. PageTime can still read, but blocking or recovery may be weaker until setup is complete."
                },
                color = if (allReady) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            if (lastReconcileAt != null) {
                Text(
                    "Last usage audit: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastReconcileAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!allReady) {
                OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Open permission setup")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            if (ready) "Ready" else "Needs attention",
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun UsageEventRow(event: UsageEventEntity) {
    val title = when (event.type) {
        UsageRepository.TYPE_EARNED -> "Reading earned"
        UsageRepository.TYPE_SPENT -> "Time spent"
        UsageRepository.TYPE_RECONCILED -> "Usage recovered"
        UsageRepository.TYPE_BLOCKED -> "Blocked at zero"
        else -> event.type
    }
    val detail = buildString {
        if (!event.packageName.isNullOrBlank()) append(event.packageName)
        if (event.seconds > 0) {
            if (isNotEmpty()) append(" · ")
            append(formatClock(event.seconds))
        }
    }.ifBlank { "PageTime" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}