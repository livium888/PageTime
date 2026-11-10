package com.pagetime.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The one way any feature asks an AI for something: the reader takes the
 * prompt to their own AI app and brings the reply back.
 *
 * PageTime never calls a model itself. Every feature that needs one shows this
 * dialog with its own [prompt], and gets the pasted reply through [onReply].
 * Whether the reply was usable is the feature's call, reported back through
 * [error]; this dialog only moves text between the clipboard and the feature.
 */
@Composable
fun AskYourAiDialog(
    title: String,
    prompt: String,
    /** Why the last pasted reply could not be used, or null. */
    error: String?,
    onReply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var notice by remember(prompt) { mutableStateOf<String?>(null) }
    var localError by remember(prompt) { mutableStateOf<String?>(null) }

    // A reply shared straight from the AI app arrives the same way a pasted one does.
    SharedReplyEffect { reply ->
        notice = null
        localError = null
        onReply(reply)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "1. Copy the prompt, or share it straight to your AI app.\n" +
                        "2. Send it there.\n" +
                        "3. Share the AI's reply to PageTime, or copy it and paste it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Prompt", prompt))
                        localError = null
                        notice = "Copied. Paste it into your AI app and send it."
                    }) { Text("Copy prompt") }
                    OutlinedButton(onClick = {
                        localError = null
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, prompt)
                                    },
                                    "Send to your AI app",
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            localError = "Couldn't open the share sheet. Use Copy instead."
                        }
                    }) { Text("Share…") }
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val reply = clipboard?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                            .orEmpty()
                        notice = null
                        if (reply.isBlank()) {
                            localError = "The clipboard is empty. Copy the AI's reply first."
                        } else if (reply.trim() == prompt.trim()) {
                            localError = "That's still the prompt. Copy the AI's reply, then paste."
                        } else {
                            localError = null
                            onReply(reply)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("I have a reply — paste it") }
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                (localError ?: error)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
