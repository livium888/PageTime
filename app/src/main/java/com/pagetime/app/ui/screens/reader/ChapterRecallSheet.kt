package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The pause at the end of a chapter: "in a sentence, what was that about?"
 *
 * What the reader writes is not saved or marked. They can look at how the
 * chapter began to check themselves, and carry on either way. See
 * [com.pagetime.app.data.learning.ChapterRecall].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterRecallSheet(
    prompt: ReaderViewModel.ChapterRecallPrompt,
    onDone: () -> Unit,
) {
    var sentence by remember(prompt) { mutableStateOf("") }
    var showOpening by remember(prompt) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Before you go on", style = MaterialTheme.typography.titleLarge)
            Text(
                "In a sentence, what was " + (prompt.title?.takeIf { it.isNotBlank() }?.let { "“$it”" }
                    ?: "chapter ${prompt.chapterNumber}") + " about?",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = sentence,
                onValueChange = { sentence = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Your own words") },
                minLines = 2,
            )
            Text(
                "Only for you: it is not saved and nothing marks it. Trying is what helps you remember.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showOpening && prompt.opening.isNotBlank()) {
                Text("How the chapter began", style = MaterialTheme.typography.labelLarge)
                Text(prompt.opening, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (prompt.opening.isNotBlank() && !showOpening) {
                    TextButton(onClick = { showOpening = true }) { Text("Show how it began") }
                } else {
                    TextButton(onClick = onDone) { Text("Skip") }
                }
                Button(onClick = onDone) { Text("Keep reading") }
            }
        }
    }
}
