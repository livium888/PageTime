package com.pagetime.app.ui.screens.reader

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * What the chapter's questions are doing right now.
 *
 * THIS IS THE BUG THIS FILE EXISTS FOR
 *
 * The first version of chapter prompts reported progress in exactly one place:
 * the label of the dropdown menu item that started it — inside a menu that
 * closes the instant it is tapped. So the reader pressed "Make questions",
 * watched nothing happen, and had no way to tell whether it was working,
 * finished, or broken. Every failure returned an empty list with no reason, so
 * "no key configured", "not indexed" and "the model refused" were all rendered
 * as the same silence.
 *
 * A long operation the reader paid for must say it is running, say when it is
 * done, and say why when it is not.
 */
@Composable
fun ChapterPromptStatus(
    message: String,
    working: Boolean,
    pendingCount: Int,
    /** The verbatim failure, when there was one, for copying rather than retyping. */
    detail: String? = null,
    onSeeQuestions: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (!working) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (detail != null) {
                        // A failure the reader can hand on intact. Retyping an
                        // API error loses exactly the part that identifies it.
                        TextButton(onClick = {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("PageTime question error", detail)
                            )
                        }) { Text("Copy error") }
                    }
                    if (pendingCount > 0) {
                        // "Where are they saved?" deserves an answer in the UI
                        // rather than a promise that they will turn up later.
                        TextButton(onClick = onSeeQuestions) { Text("See them") }
                    }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}
