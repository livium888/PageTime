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
    onExplain: () -> Unit,
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
                "Want to explain the key ideas from $chapterLabel in your own words? The Feynman technique: if you can explain it simply, you understand it.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onExplain) { Text("Explain what you learned") }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}
