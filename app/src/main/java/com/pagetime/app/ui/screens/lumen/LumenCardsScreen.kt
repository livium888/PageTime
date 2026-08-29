package com.pagetime.app.ui.screens.lumen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.LumenRepository
import com.pagetime.app.data.local.LumenCardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LumenViewModel(
    private val repository: LumenRepository
) : ViewModel() {

    val cards = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addContext(cardId: String, text: String) {
        viewModelScope.launch { repository.addContext(cardId, text, null) }
    }

    fun updateText(cardId: String, front: String, back: String) {
        viewModelScope.launch { repository.updateText(cardId, front, back) }
    }

    fun delete(cardId: String) {
        viewModelScope.launch { repository.delete(cardId) }
    }

    class Factory(private val repository: LumenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LumenViewModel(repository) as T
    }
}

/**
 * The slip box: every captured Lumen card, newest change first. Cards evolve
 * here — edit text, append context snippets, or delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumenCardsScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit = {}
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PageTimeApp
    val vm: LumenViewModel = viewModel(
        factory = LumenViewModel.Factory(app.container.lumenRepository)
    )
    val cards by vm.cards.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<LumenCardEntity?>(null) }
    var addingContext by remember { mutableStateOf<LumenCardEntity?>(null) }
    var deleting by remember { mutableStateOf<LumenCardEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lumen cards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (cards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.NoteAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No cards yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "While reading, open Options → New Lumen card\nto capture the idea in front of you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cards, key = { it.id }) { card ->
                    LumenCardRow(
                        card = card,
                        onOpen = { onOpenSource(card.bookId) },
                        onEdit = { editing = card },
                        onAddContext = { addingContext = card },
                        onDelete = { deleting = card }
                    )
                }
            }
        }
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onSave = { front, back ->
                vm.updateText(card.id, front, back)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    addingContext?.let { card ->
        AddContextDialog(
            cardFront = card.front,
            onAdd = { text ->
                vm.addContext(card.id, text)
                addingContext = null
            },
            onDismiss = { addingContext = null }
        )
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete card?") },
            text = { Text("\u201C${card.front}\u201D will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(card.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LumenCardRow(
    card: LumenCardEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddContext: () -> Unit,
    onDelete: () -> Unit
) {
    val snippets = remember(card.snippetsJson) {
        LumenCapture.snippetsFromJson(card.snippetsJson)
    }
    Card(onClick = onOpen) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Tap to jump to source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDate(card.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                card.front,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (card.back.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    card.back,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (snippets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "+${snippets.size} context snippet${if (snippets.size == 1) "" else "s"} — latest ${formatDate(snippets.last().addedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onAddContext) {
                    Text("+ Context")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCardDialog(
    card: LumenCardEntity,
    onSave: (front: String, back: String) -> Unit,
    onDismiss: () -> Unit
) {
    var front by remember(card.id) { mutableStateOf(card.front) }
    var back by remember(card.id) { mutableStateOf(card.back) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit card") },
        text = {
            Column {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Title / question") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text("Note") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(front, back) },
                enabled = front.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddContextDialog(
    cardFront: String,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add context") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Appends a dated snippet to \u201C$cardFront\u201D — the card keeps its history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("New snippet") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank()
            ) { Text("Append") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private val dateFormat by lazy { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
