package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChapterReviewPrompt(
    chapterLabel: String,
    onCreateCard: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Chapter complete", style = MaterialTheme.typography.titleMedium)
            Text(
                "Want to write one question about $chapterLabel? A short recall check helps the important ideas stick.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onCreateCard) { Text("Create a recall card") }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}
