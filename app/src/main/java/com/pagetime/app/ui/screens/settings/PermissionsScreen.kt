package com.pagetime.app.ui.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageAccessEnabled by remember { mutableStateOf(hasUsageAccess(context)) }

    // Re-check when returning from the system settings screens.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityEnabled(context)
                overlayEnabled = Settings.canDrawOverlays(context)
                usageAccessEnabled = hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions & setup") },
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
            Text(
                "PageTime needs three special permissions to enforce your reading goal. None of them let the app read your data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionCard(
                title = "Accessibility service",
                description = "Detects when a blocked app opens so it can send you back to the reader.",
                granted = accessibilityEnabled,
                buttonLabel = if (accessibilityEnabled) "Open settings" else "Enable",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionCard(
                title = "Display over other apps",
                description = "Lets PageTime show the time is up screen over a blocked app.",
                granted = overlayEnabled,
                buttonLabel = if (overlayEnabled) "Open settings" else "Allow",
                onClick = {
                    val uri = Uri.parse("package:${context.packageName}")
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                    )
                }
            )

            PermissionCard(
                title = "Usage access",
                description = "The honest clock. Android keeps recording which apps you opened even if PageTime itself is stopped, so PageTime can catch up on time spent in blocked apps after a crash, force-stop, or restart. Without it the counter can't know what happened while it wasn't running.",
                granted = usageAccessEnabled,
                buttonLabel = if (usageAccessEnabled) "Open settings" else "Enable",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                }
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.contains("AppBlockerService") }
}
