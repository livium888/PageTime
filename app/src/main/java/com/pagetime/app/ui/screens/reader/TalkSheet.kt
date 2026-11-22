package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagetime.app.data.talk.TalkItThrough
import com.pagetime.app.ui.components.SharedReplyEffect
import kotlinx.coroutines.delay

/**
 * "Talk it through": choose how deep, send the prompt to your AI, watch the
 * stopwatch while you talk, and bring the receipt back for the time it earns.
 * See [TalkItThrough] for how the credit is worked out.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TalkSheet(vm: TalkViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A receipt shared straight from the AI app is read like a pasted one.
    SharedReplyEffect(vm::receipt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Back from the AI app with the receipt copied: read it without being
        // asked. The clipboard can only be read once this window has focus.
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, state.step) {
            if (focused && state.step == TalkViewModel.Step.TALKING) vm.pickUpCopiedReceipt(context)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Talk it through", style = MaterialTheme.typography.titleLarge)
            when {
                state.busy -> CircularProgressIndicator()
                state.step == TalkViewModel.Step.RESULT -> ResultStep(state, onDismiss)
                state.step == TalkViewModel.Step.TALKING -> Talking(state, vm)
                state.error != null && state.basePrompt.isEmpty() ->
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                else -> Choose(state, vm)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Choose(state: TalkViewModel.UiState, vm: TalkViewModel) {
    val context = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }
    Text(
        "${state.sectionLabel} · about ${"%,d".format(java.util.Locale.US, state.words)} words",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Your AI will ask you questions one at a time until it is satisfied, and finish with a receipt. " +
            "PageTime times the conversation and adds the time to today's reading.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("How deep?", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..3).forEach { n ->
            FilterChip(
                selected = state.questions == n,
                onClick = { vm.setQuestions(n) },
                label = { Text(if (n == 1) "1 question" else "$n questions") },
            )
        }
    }
    Text(
        "About ${TalkItThrough.estimateMinutes(state.questions)} minutes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Talking(state: TalkViewModel.UiState, vm: TalkViewModel) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val seconds = ((now - state.startedAt) / 1000).coerceAtLeast(0)
    Text(
        TalkItThrough.clock(seconds),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Talking it through with your AI · ${state.sectionLabel}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Card(Modifier.fillMaxWidth()) {
        Text(
            "When your AI is satisfied it ends with a receipt that starts PAGETIME RECEIPT. Copy it and come " +
                "back here: it is picked up on its own. This clock keeps running while you are away.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
    OutlinedButton(onClick = { vm.pasteReceipt(context) }, modifier = Modifier.fillMaxWidth()) {
        Text("I have the receipt — paste it")
    }
    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = vm::cancel) { Text("Cancel — no credit") }
}

@Composable
private fun ResultStep(state: TalkViewModel.UiState, onDismiss: () -> Unit) {
    val credit = state.credit ?: return
    val receipt = state.receipt ?: return
    Text(receipt.verdict.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(
        if (credit.seconds > 0) "+${TalkItThrough.clock(credit.seconds)} earned" else "No time earned",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    credit.withheld?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CreditLine("Your stopwatch", TalkItThrough.clock(credit.stopwatch))
            CreditLine(
                "${receipt.exchanges} exchanges × ${TalkItThrough.SECONDS_PER_EXCHANGE} s",
                TalkItThrough.clock(credit.byExchanges),
            )
            CreditLine("Cap", TalkItThrough.clock(credit.cap))
            CreditLine("Credited (the smallest)", TalkItThrough.clock(credit.seconds), bold = true)
        }
    }
    if (receipt.shaky.isNotEmpty()) {
        Text("Still shaky", style = MaterialTheme.typography.labelLarge)
        receipt.shaky.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Making a card or two on these is a good next step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun CreditLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
    }
}
