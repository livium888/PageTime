package com.pagetime.app.ui.screens.lumen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pagetime.app.data.LumenConnections
import com.pagetime.app.data.LumenCoach
import com.pagetime.app.data.LumenLesson
import com.pagetime.app.data.LumenRegister
import com.pagetime.app.data.LumenRepository
import com.pagetime.app.data.LumenSearch
import com.pagetime.app.data.LumenThread
import com.pagetime.app.data.local.LumenCardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LumenViewModel(
    private val repository: LumenRepository
) : ViewModel() {

    /** Which slip box is open; 0 = "all boxes". */
    private val _selectedBox = MutableStateFlow(0)
    val selectedBox = _selectedBox.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val cards = _selectedBox
        .flatMapLatest { box ->
            if (box <= 0) repository.observeAll() else repository.observeBox(box)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dueCount = repository.observeDueCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun selectBox(box: Int) {
        _selectedBox.value = box
    }

    suspend fun boxRange(): IntRange = repository.boxRange()

    fun addContext(cardId: String, text: String) {
        viewModelScope.launch { repository.addContext(cardId, text, null) }
    }

    fun updateText(cardId: String, front: String, back: String) {
        viewModelScope.launch { repository.updateText(cardId, front, back) }
    }

    fun delete(cardId: String) {
        viewModelScope.launch { repository.deleteWithLinks(cardId) }
    }

    fun fileBehind(cardId: String, behindCardId: String) {
        viewModelScope.launch { repository.fileBehind(cardId, behindCardId) }
    }

    fun moveToBox(cardId: String, box: Int) {
        viewModelScope.launch { repository.moveToBox(cardId, box) }
    }

    fun saveManual(front: String, back: String, behindCardId: String? = null) {
        viewModelScope.launch {
            val box = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.boxRange().lastOrNull() ?: 1 }
            repository.saveManual(box, front, back, behindCardId)
        }
    }

    fun link(cardId: String, otherId: String) {
        viewModelScope.launch { repository.link(cardId, otherId) }
    }

    fun connectionCandidates(card: LumenCardEntity, onLoaded: (List<com.pagetime.app.data.LumenCandidate>) -> Unit) {
        viewModelScope.launch {
            onLoaded(LumenConnections.rank(card, repository.observeAll().first()))
        }
    }

    fun linkedCards(card: LumenCardEntity, onLoaded: (List<LumenCardEntity>) -> Unit) {
        viewModelScope.launch {
            val ids = LumenCapture.linksFromJson(card.linksJson)
            onLoaded(if (ids.isEmpty()) emptyList() else repository.cardsByIds(ids))
        }
    }

    /** The literature box: sources with their citing cards, loaded on demand. */
    fun sources(onLoaded: (List<com.pagetime.app.data.LumenSources.Source>) -> Unit) {
        viewModelScope.launch {
            onLoaded(repository.sources())
        }
    }

    class Factory(private val repository: LumenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LumenViewModel(repository) as T
    }
}

/**
 * The slip box: numbered boxes along the top, cards filed at stable Luhmann
 * addresses ("21/2a7"), pop-up card details with cross-reference links, and an
 * optional FSRS training session.
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
    val selectedBox by vm.selectedBox.collectAsStateWithLifecycle()
    val dueCount by vm.dueCount.collectAsStateWithLifecycle()

    var detailCard by remember { mutableStateOf<LumenCardEntity?>(null) }
    var editing by remember { mutableStateOf<LumenCardEntity?>(null) }
    var addingContext by remember { mutableStateOf<LumenCardEntity?>(null) }
    var deleting by remember { mutableStateOf<LumenCardEntity?>(null) }
    var linking by remember { mutableStateOf<LumenCardEntity?>(null) }
    var findingConnections by remember { mutableStateOf<LumenCardEntity?>(null) }
    var moving by remember { mutableStateOf<LumenCardEntity?>(null) }
    var moreActions by remember { mutableStateOf<LumenCardEntity?>(null) }
    var composing by remember { mutableStateOf(false) }
    var composingBehind by remember { mutableStateOf<LumenCardEntity?>(null) }
    var filingBehind by remember { mutableStateOf<LumenCardEntity?>(null) }
    var studying by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pullingThread by remember { mutableStateOf<LumenCardEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Slip box") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }) {
                        Icon(
                            if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                            contentDescription = "Search"
                        )
                    }
                    IconButton(onClick = { sources = true }) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = "Sources")
                    }
                    IconButton(onClick = { register = true }) {
                        Icon(Icons.Outlined.ListAlt, contentDescription = "Register")
                    }
                    IconButton(onClick = { studying = true }) {
                        Icon(Icons.Outlined.School, contentDescription = "Learn the method")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { composing = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "New card")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BoxTabs(
                selected = selectedBox,
                dueCount = dueCount,
                onSelect = { vm.selectBox(it) }
            )
            if (searching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search address, note, quote, keyword…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            val visibleCards = remember(cards, searchQuery) {
                LumenSearch.filter(cards, searchQuery)
            }
            if (visibleCards.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (searchQuery.isNotBlank()) Icons.Outlined.Search else Icons.Outlined.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                searchQuery.isNotBlank() -> "No notes match “${searchQuery.take(30)}”"
                                selectedBox == 0 -> "The slip box is empty"
                                else -> "Box $selectedBox is empty"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (searchQuery.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "While reading, open Options → New Lumen card\nto file the idea in front of you, or tap + to\nwrite a card at your desk.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleCards, key = { it.id }) { card ->
                        LumenCardRow(
                            card = card,
                            onOpen = { detailCard = card },
                            onDelete = { deleting = card }
                        )
                    }
                }
            }
        }
    }

    detailCard?.let { card ->
        CardDetailDialog(
            card = card,
            onClose = { detailCard = null },
            onEdit = {
                editing = card
                detailCard = null
            },
            onAddContext = {
                addingContext = card
                detailCard = null
            },
            onLink = {
                linking = card
                detailCard = null
            },
            onFindConnections = {
                findingConnections = card
                detailCard = null
            },
            onMore = {
                moreActions = card
                detailCard = null
            },
            onMove = {
                moving = card
                detailCard = null
            },
            onFileBehind = {
                filingBehind = card
                detailCard = null
            },
            onDelete = {
                deleting = card
                detailCard = null
            },
            onPullThread = {
                pullingThread = card
                detailCard = null
            },
            onOpenSource = {
                detailCard = null
                onOpenSource(card.bookId)
            },
            onOpenLinked = { linked ->
                detailCard = linked
            },
            loadLinked = { c, cb -> vm.linkedCards(c, cb) }
        )
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

    findingConnections?.let { card ->
        ConnectionCandidatesDialog(
            card = card,
            onLoad = { callback -> vm.connectionCandidates(card, callback) },
            onLink = { other ->
                vm.link(card.id, other.card.id)
                findingConnections = null
            },
            onDismiss = { findingConnections = null }
        )
    }

    linking?.let { card ->
        LinkCardDialog(
            card = card,
            allCards = cards,
            onLink = { other ->
                vm.link(card.id, other.id)
                linking = null
            },
            onDismiss = { linking = null }
        )
    }

    filingBehind?.let { card ->
        LinkCardDialog(
            card = card,
            allCards = cards.filter { it.box == card.box },
            title = "File ${card.indexNumber.ifBlank { "?" }} behind…",
            onLink = { behind ->
                vm.fileBehind(card.id, behind.id)
                filingBehind = null
            },
            onDismiss = { filingBehind = null }
        )
    }

    moreActions?.let { card ->
        CardActionsDialog(
            card = card,
            onFileBehind = { moreActions = null; filingBehind = card },
            onPullThread = { moreActions = null; pullingThread = card },
            onMove = { moreActions = null; moving = card },
            onDelete = { moreActions = null; deleting = card },
            onDismiss = { moreActions = null }
        )
    }

    moving?.let { card ->
        MoveBoxDialog(
            card = card,
            currentRange = 1..maxOf(1, selectedBox),
            onMove = { box ->
                vm.moveToBox(card.id, box)
                moving = null
            },
            onDismiss = { moving = null }
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

    if (composing) {
        ComposeCardDialog(
            behindLabel = null,
            onSave = { front, back ->
                vm.saveManual(front, back)
                composing = false
            },
            onDismiss = { composing = false }
        )
    }

    composingBehind?.let { behind ->
        ComposeCardDialog(
            behindLabel = behind.indexNumber.ifBlank { null },
            onSave = { front, back ->
                vm.saveManual(front, back, behind.id)
                composingBehind = null
            },
            onDismiss = { composingBehind = null }
        )
    }

    if (studying) {
        StudyDialog(
            cards = cards,
            onFileBehind = { card ->
                studying = false
                composingBehind = card
            },
            onDismiss = { studying = false }
        )
    }

    if (register) {
        RegisterDialog(
            cards = cards,
            onOpenCard = { card ->
                register = false
                detailCard = card
            },
            onDismiss = { register = false }
        )
    }

    if (sources) {
        SourcesDialog(
            onLoad = { onLoaded -> vm.sources(onLoaded) },
            onOpenCard = { card ->
                sources = false
                detailCard = card
            },
            onDismiss = { sources = false }
        )
    }

    pullingThread?.let { root ->
        ThreadDialog(
            root = root,
            allCards = cards,
            onDismiss = { pullingThread = null }
        )
    }
}

@Composable
private fun BoxTabs(
    selected: Int,
    dueCount: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text("All") }
        )
        FilterChip(
            selected = selected == 1,
            onClick = { onSelect(1) },
            label = { Text("Box 1") }
        )
        FilterChip(
            selected = selected == 2,
            onClick = { onSelect(2) },
            label = { Text("Box 2") }
        )
        FilterChip(
            selected = selected == 3,
            onClick = { onSelect(3) },
            label = { Text("Box 3") }
        )
        if (dueCount > 0) {
            FilterChip(
                selected = false,
                onClick = { onSelect(0) },
                label = { Text("$dueCount due") }
            )
        }
    }
}

@Composable
private fun LumenCardRow(
    card: LumenCardEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val snippets = remember(card.snippetsJson) {
        LumenCapture.snippetsFromJson(card.snippetsJson)
    }
    val links = remember(card.linksJson) {
        LumenCapture.linksFromJson(card.linksJson)
    }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The index number is the card's identity — show it prominently.
                Text(
                    card.indexNumber.ifBlank { "?" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "BOX ${card.box}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (card.dueAt != null) {
                    Icon(
                        Icons.Outlined.School,
                        contentDescription = "In training",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (links.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = "${links.size} links",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                    card.front,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            if (card.back.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    card.back,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (card.quote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "From source · ${card.quote.replace(Regex("\\s+"), " ").take(110)}${if (card.quote.length > 110) "…" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (snippets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "+${snippets.size} context — latest ${formatDate(snippets.last().addedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CardDetailDialog(
    card: LumenCardEntity,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onAddContext: () -> Unit,
    onLink: () -> Unit,
    onFindConnections: () -> Unit,
    onMore: () -> Unit,
    onMove: () -> Unit,
    onFileBehind: () -> Unit,
    onDelete: () -> Unit,
    onPullThread: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenLinked: (LumenCardEntity) -> Unit,
    loadLinked: (LumenCardEntity, (List<LumenCardEntity>) -> Unit) -> Unit
) {
    var linked by remember(card.id) { mutableStateOf<List<LumenCardEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(card.id) {
        loadLinked(card) { linked = it }
    }
    val snippets = remember(card.snippetsJson) {
        LumenCapture.snippetsFromJson(card.snippetsJson)
    }
    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.large,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.indexNumber.ifBlank { "?" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "BOX ${card.box}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Permanent address · edit the thought, not the identity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(14.dp)) {
                Text(
                    "THE IDEA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    card.front,
                    style = MaterialTheme.typography.titleLarge
                )
                if (card.back.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        card.back,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                }
                if (card.quote.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "\u201C${card.quote.take(300)}${if (card.quote.length > 300) "…" else ""}\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenSource, contentPadding = PaddingValues(0.dp)) {
                        Icon(
                            Icons.Outlined.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Jump to source")
                    }
                }
                if (card.keywords.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("REGISTER TERMS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(card.keywords, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (linked.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Linked notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    linked.forEach { other ->
                        Text(
                            "${other.indexNumber.ifBlank { "?" }} — ${other.front}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onOpenLinked(other) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
                if (snippets.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Evolution",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    snippets.forEach { snippet ->
                        Text(
                            "${formatDate(snippet.addedAt)} — ${snippet.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onAddContext) { Text("+ Context") }
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onLink, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Link", maxLines = 1)
                }
                TextButton(onClick = onFindConnections, modifier = Modifier.weight(1f)) {
                    Text("Connect", maxLines = 1)
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                }
            }
        }
    )
}

@Composable
private fun CardActionsDialog(
    card: LumenCardEntity,
    onFileBehind: () -> Unit,
    onPullThread: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actions for ${card.indexNumber.ifBlank { "?" }}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onFileBehind, modifier = Modifier.fillMaxWidth()) { Text("File behind", maxLines = 1) }
                TextButton(onClick = onPullThread, modifier = Modifier.fillMaxWidth()) { Text("Pull thread", maxLines = 1) }
                TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) { Text("Move to another box", maxLines = 1) }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete card", maxLines = 1) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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

@Composable
private fun LinkCardDialog(
    card: LumenCardEntity,
    allCards: List<LumenCardEntity>,
    onLink: (LumenCardEntity) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Link \u201C${card.indexNumber}\u201D to…"
) {
    val existing = remember(card.linksJson) { LumenCapture.linksFromJson(card.linksJson) }
    val candidates = allCards
        .filter { it.id != card.id }
        .sortedWith(compareBy<LumenCardEntity> { it.box }.thenBy { it.indexNumber })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (candidates.isEmpty()) {
                Text("No other cards to link yet.")
            } else {
                LazyColumn {
                    items(candidates, key = { it.id }) { other ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLink(other) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                other.indexNumber.ifBlank { "?" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                other.front,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (other.id in existing) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "linked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun MoveBoxDialog(
    card: LumenCardEntity,
    currentRange: IntRange,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val boxes = (1..maxOf(3, currentRange.last + 1)).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to box") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                boxes.forEach { box ->
                    FilterChip(
                        selected = box == card.box,
                        onClick = { onMove(box) },
                        label = { Text("$box") }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ComposeCardDialog(
    behindLabel: String?,
    onSave: (front: String, back: String) -> Unit,
    onDismiss: () -> Unit
) {
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (behindLabel != null) "File behind $behindLabel" else "New card")
        },
        text = {
            Column {
                if (behindLabel != null) {
                    Text(
                        "This card continues the line at $behindLabel — it gets the next address in that branch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Title / question — your own words") },
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
            ) { Text("File card") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * The method coach: Luhmann's practice as lessons, plus the next concrete
 * step for THIS box. Not a quiz — a teaching conversation.
 */
@Composable
private fun StudyDialog(
    cards: List<LumenCardEntity>,
    onFileBehind: (LumenCardEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var lessonIndex by remember { mutableStateOf(0) }
    val lesson = LumenCoach.lessons[lessonIndex]
    val nextStep = remember(cards.hashCode()) { LumenCoach.nextStep(cards) }
    val tip = remember(cards.hashCode()) {
        LumenCoach.tips[(cards.size + LumenCoach.tips.size) % LumenCoach.tips.size]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "Working with the slip box",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${lessonIndex + 1}/${LumenCoach.lessons.size} — ${lesson.title}"
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    lesson.body,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Try it now",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    lesson.practice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lessonIndex == 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your box right now",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        nextStep ?: "Capture your first card while reading.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (lessonIndex < LumenCoach.lessons.size - 1) {
                TextButton(onClick = { lessonIndex++ }) { Text("Next") }
            } else {
                TextButton(onClick = onDismiss) { Text("Got it") }
            }
        },
        dismissButton = {
            if (lessonIndex > 0) {
                TextButton(onClick = { lessonIndex-- }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

/**
 * The Register: Luhmann's keyword index. One line per keyword, pointing at
 * entry addresses; tap an entry to open the card it opens onto.
 */
@Composable
private fun RegisterDialog(
    cards: List<LumenCardEntity>,
    onOpenCard: (LumenCardEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(cards.hashCode()) { LumenRegister.build(cards) }
    val byId = remember(cards.hashCode()) { cards.associateBy { it.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "The register grows by itself as you capture cards — every keyword gets an entry pointing at its address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(entries, key = { it.keyword }) { entry ->
                        val firstCard = entry.cardIds.firstNotNullOfOrNull { byId[it] }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = firstCard != null) {
                                    firstCard?.let(onOpenCard)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.keyword,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.addresses.joinToString(
                                    ", ",
                                    limit = 3,
                                    truncated = "…"
                                ) { it },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Pulling a thread: the root slip plus everything filed behind it, in shelf
 * order — the outline falls out of the chain. Copyable as plain text.
 */
@Composable
private fun ThreadDialog(
    root: LumenCardEntity,
    allCards: List<LumenCardEntity>,
    onDismiss: () -> Unit
) {
    val steps = remember(root.id, allCards.hashCode()) { LumenThread.pull(root, allCards) }
    val rendered = remember(root.id, allCards.hashCode()) { LumenThread.render(steps) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Thread from ${root.indexNumber.ifBlank { "?" }}")
                Text(
                    "${steps.size} slip${if (steps.size == 1) "" else "s"} filed behind it, in box order",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                Text(
                    rendered,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Lumen thread", rendered)
                    )
                    copied = true
                }
            ) { Text(if (copied) "Copied" else "Copy outline") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ConnectionCandidatesDialog(
    card: LumenCardEntity,
    onLoad: ((List<com.pagetime.app.data.LumenCandidate>) -> Unit) -> Unit,
    onLink: (com.pagetime.app.data.LumenCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    var candidates by remember { mutableStateOf<List<com.pagetime.app.data.LumenCandidate>?>(null) }
    androidx.compose.runtime.LaunchedEffect(card.id) { onLoad { candidates = it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Possible connections")
                Text(
                    "Local suggestions for ${card.indexNumber.ifBlank { "?" }} — review before linking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            when (val items = candidates) {
                null -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                emptyList<List<com.pagetime.app.data.LumenCandidate>>() -> Text(
                    "No strong local matches yet. Try adding a clearer title or keywords, then search again.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn {
                    items(items, key = { it.card.id }) { candidate ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLink(candidate) }
                                .padding(vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    candidate.card.indexNumber.ifBlank { "?" },
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    candidate.card.front,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (candidate.reasons.isNotEmpty()) {
                                Text(
                                    candidate.reasons.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * The literature box: one bibliographic slip per source, with the notes that
 * cite it. Luhmann's second Zettelkasten, powered by the library's own data.
 */
@Composable
private fun SourcesDialog(
    onLoad: ((List<com.pagetime.app.data.LumenSources.Source>) -> Unit) -> Unit,
    onOpenCard: (LumenCardEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var loaded by remember { mutableStateOf<List<com.pagetime.app.data.LumenSources.Source>?>(null) }
    var expandedSource by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoad { loaded = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Sources")
                Text(
                    "The literature box — every source your notes cite",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            val sources = loaded
            when {
                sources == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
                sources.isEmpty() -> {
                    Text(
                        "No sourced notes yet. Cards captured while reading (Options \u2192 New Lumen card) appear here, grouped by the book they came from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 380.dp)) {
                        sources.forEach { source ->
                            val expanded = expandedSource == source.bookId
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedSource = if (expanded) null else source.bookId }
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            source.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (source.author.isNotBlank()) {
                                            Text(
                                                source.author,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        "${source.cards.size} note${if (source.cards.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (expanded) "\u25BC" else "\u25B6",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (expanded) {
                                    source.cards.forEach { card ->
                                        Text(
                                            "${card.indexNumber.ifBlank { "?" }}  ${card.front}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .clickable { onOpenCard(card) }
                                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val sources = loaded
            val context = androidx.compose.ui.platform.LocalContext.current
            if (sources != null && sources.isNotEmpty()) {
                TextButton(onClick = {
                    val text = sources.joinToString("\n\n") { com.pagetime.app.data.LumenSources.render(it) }
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Lumen sources", text)
                    )
                }) { Text("Copy all") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private val dateFormat by lazy { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
