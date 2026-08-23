package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardCreationSheet(
    bookTitle: String,
    chapterLabel: String?,
    suggestedPrompt: String? = null,
    onSave: (prompt: String, answer: String, explanation: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember(suggestedPrompt) { mutableStateOf(suggestedPrompt.orEmpty()) }
    var answer by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Create a review card", style = MaterialTheme.typography.headlineSmall)
            Text(
                "$bookTitle${chapterLabel?.let { " · $it" }.orEmpty()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Write a question you can answer later without looking back. This card will be scheduled offline by FSRS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Question") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Answer") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                value = explanation,
                onValueChange = { explanation = it },
                label = { Text("Optional explanation") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Button(
                onClick = { onSave(prompt, answer, explanation.takeIf { it.isNotBlank() }) },
                enabled = prompt.isNotBlank() && answer.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save review card") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
