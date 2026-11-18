package com.pagetime.app.ui.screens.keepreading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.keepreading.KeepReading
import com.pagetime.app.data.keepreading.KeepReading.Direction
import com.pagetime.app.data.keepreading.KeepReading.Kind
import com.pagetime.app.data.keepreading.KeepReading.Suggestion
import com.pagetime.app.ui.components.SharedReplyEffect

/** The offer on the last page: one line, and the way into the sheet. It never blocks the text. */
@Composable
fun KeepReadingCard(
    title: String,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text("You finished “${title.take(60)}”", style = MaterialTheme.typography.titleMedium)
            Text(
                "Where would you like to go next? Your AI can suggest more to read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onChoose) { Text("Choose a direction") }
            }
        }
    }
}

/** Direction, kinds, an editable prompt, the hand-off to the AI app, and the answers. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KeepReadingSheet(
    finished: KeepReading.Finished,
    onDismiss: () -> Unit,
    vm: KeepReadingViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(finished) { vm.open(finished) }

    // A reply shared straight from the AI app is read the same way.
    SharedReplyEffect(vm::reply)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Back from the AI app with the reply copied: read it without being
        // asked. The clipboard can only be read once this window has focus.
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, state.step) {
            if (focused && state.step == KeepReadingViewModel.Step.AWAITING) vm.pickUpCopiedReply(context)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("You finished “${finished.title}”", style = MaterialTheme.typography.titleLarge)

            if (state.step == KeepReadingViewModel.Step.RESULTS) {
                Results(state, vm, onDone = onDismiss)
            } else {
                Choose(state, vm)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Choose(state: KeepReadingViewModel.UiState, vm: KeepReadingViewModel) {
    val context = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }

    Text("Where next?", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Direction.entries.forEach { d ->
            FilterChip(selected = state.direction == d, onClick = { vm.setDirection(d) }, label = { Text(d.label) })
        }
    }
    Text("What kind of thing?", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kind.entries.forEach { k ->
            FilterChip(selected = k in state.kinds, onClick = { vm.toggleKind(k) }, label = { Text(k.label) })
        }
    }

    TextButton(onClick = { showPrompt = !showPrompt }) {
        Text(if (showPrompt) "Hide the prompt" else "See and edit the prompt")
    }
    if (showPrompt) {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = vm::edit,
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            maxLines = 14,
        )
        if (state.edited != null) TextButton(onClick = vm::resetEdits) { Text("Start from the suggested prompt") }
    }

    Button(onClick = { vm.send(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(state.aiAppLabel?.let { "Ask $it" } ?: "Ask my AI")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::copyPrompt) { Text("Copy the prompt") }
        if (state.aiAppLabel != null) {
            OutlinedButton(onClick = { vm.chooseAnotherApp(context) }) { Text("Another app") }
        }
    }
    OutlinedButton(onClick = { vm.pasteReply(context) }, modifier = Modifier.fillMaxWidth()) {
        Text("I already have a reply — paste it")
    }
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Results(state: KeepReadingViewModel.UiState, vm: KeepReadingViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    Text(
        "${state.suggestions.size} things to read next. “Find it” opens a search, because an AI can get a title or a link wrong.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.suggestions.forEach { s ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    s.kind.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                if (s.creator.isNotBlank()) {
                    Text(s.creator, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.why.isNotBlank()) Text(s.why, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.find(context, s) }) { Text("Find it") }
                    if (s.title.lowercase() in state.saved) {
                        Text("Saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = { vm.save(listOf(s)) }) { Text("Save for later") }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.save(state.suggestions) }) { Text("Save all") }
        OutlinedButton(onClick = vm::backToChoose) { Text("Ask another way") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}
