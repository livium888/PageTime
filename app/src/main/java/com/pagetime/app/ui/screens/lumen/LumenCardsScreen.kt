package com.pagetime.app.ui.screens.lumen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenAddress
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.LumenConnections
import com.pagetime.app.data.LumenCoach
import com.pagetime.app.data.LumenGraph
import com.pagetime.app.data.LumenGraphEdgeKind
import com.pagetime.app.data.LumenGraphLayout
import com.pagetime.app.data.LumenManuscript
import com.pagetime.app.data.LumenMentions
import com.pagetime.app.data.LumenOnboarding
import com.pagetime.app.data.LumenReferences
import com.pagetime.app.data.LumenRegister
import com.pagetime.app.data.LumenRepository
import com.pagetime.app.data.LumenRole
import com.pagetime.app.data.LumenSearch
import com.pagetime.app.data.LumenTree
import com.pagetime.app.data.LumenStructureMap
import com.pagetime.app.data.LumenThread
import com.pagetime.app.data.ManuscriptEntry
import com.pagetime.app.data.local.LumenCardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A newcomer-help confirmation awaiting the user's answer before an action runs. */
private data class LumenHelpPrompt(
    val action: LumenOnboarding.Action,
    val onConfirm: () -> Unit
)

class LumenViewModel(
    private val repository: LumenRepository,
    private val settingsRepository: com.pagetime.app.data.local.SettingsRepository
) : ViewModel() {

    /** Which slip box is open (1-based). */
    private val _selectedBox = MutableStateFlow(1)
    val selectedBox = _selectedBox.asStateFlow()

    /** The boxes that actually contain cards — unused boxes are not shown. */
    val boxes: StateFlow<List<Int>> = repository.observeAll()
        .map { all ->
            if (all.isEmpty()) listOf(1) else all.map { it.box }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(1))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val cards = _selectedBox
        .flatMapLatest { box ->
            if (box <= 0) repository.observeAll() else repository.observeBox(box)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dueCount = repository.observeDueCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Structure maps (hub notes) across every box — the main index's heads. */
    val hubs = repository.observeHubs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Newcomer help: explains actions before they run until turned off in Settings. */
    val helpEnabled = settingsRepository.settings
        .map { it.helpEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setHelpEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHelpEnabled(value) }
    }

    fun selectBox(box: Int) {
        _selectedBox.value = box
    }

    fun setHub(cardId: String, isHub: Boolean) {
        viewModelScope.launch { repository.setHub(cardId, isHub) }
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

    /** All cards across every box, for the writing desk (loaded on demand). */
    fun allCards(onLoaded: (List<LumenCardEntity>) -> Unit) {
        viewModelScope.launch {
            onLoaded(repository.observeAll().first())
        }
    }

    /** The literature box: sources with their citing cards, loaded on demand. */
    fun sources(onLoaded: (List<com.pagetime.app.data.LumenSources.Source>) -> Unit) {
        viewModelScope.launch {
            onLoaded(repository.sources())
        }
    }

    /** Lossless JSON backup of the whole box, built on the IO dispatcher. */
    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.exportJson() }
            onReady(json)
        }
    }

    class Factory(
        private val repository: LumenRepository,
        private val settingsRepository: com.pagetime.app.data.local.SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LumenViewModel(repository, settingsRepository) as T
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
        factory = LumenViewModel.Factory(
            app.container.lumenRepository,
            app.container.settingsRepository
        )
    )
    val cards by vm.cards.collectAsStateWithLifecycle()
    val selectedBox by vm.selectedBox.collectAsStateWithLifecycle()
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val dueCount by vm.dueCount.collectAsStateWithLifecycle()
    val hubs by vm.hubs.collectAsStateWithLifecycle()
    val helpEnabled by vm.helpEnabled.collectAsStateWithLifecycle()

    // True Luhmann shelf order (21 → 21a → 21a1 → 21b → 22 → 210), not Room's
    // lexicographic indexNumber sort (which scatters branches and puts 10
    // before 2). Shared by the shelf list, the map, and slip-by-slip walking.
    val shelfCards = remember(cards) { LumenAddress.shelfOrder(cards) }

    // Every card knows its thread: the trunk line it hangs under and that
    // line's name — the trunk slip's title, exactly like "21 Literatur" in
    // Luhmann's box. Branch cards show it so you always know what line of
    // thought you're reading; the trunk's title is its name, editable like
    // any slip (edit the thought, never the address).
    val threadLabels = remember(shelfCards) {
        // Trunk labels flow down each tree in one pass (O(n)) instead of
        // running an O(n) threadPath per slip (O(n²) as a box grows): every
        // branch card inherits the name of its trunk line. Roots themselves
        // carry no label, exactly like the per-card threadPath it replaces.
        val roots = LumenTree.build(shelfCards)
        val labels = mutableMapOf<String, Pair<String, String>>()
        fun label(node: LumenTree.Node, trunk: Pair<String, String>) {
            for (child in node.children) {
                labels[child.card.id] = trunk
                label(child, trunk)
            }
        }
        for (root in roots) {
            label(root, root.card.indexNumber to root.card.front)
        }
        labels
    }

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
    var viewMode by remember { mutableStateOf("shelf") }
    var pullingThread by remember { mutableStateOf<LumenCardEntity?>(null) }
    var structureMapHub by remember { mutableStateOf<LumenCardEntity?>(null) }
    var writing by remember { mutableStateOf(false) }
    var writeCards by remember { mutableStateOf<List<LumenCardEntity>?>(null) }
    var helpPrompt by remember { mutableStateOf<LumenHelpPrompt?>(null) }

    /**
     * Runs [go] right away when help is off; otherwise shows a plain-language
     * explainer and confirmation first, so a newcomer knows what an action
     * does before anything is changed.
     */
    fun runWithHelp(
        action: LumenOnboarding.Action,
        go: () -> Unit,
    ) {
        if (helpEnabled) helpPrompt = LumenHelpPrompt(action, go) else go()
    }

    // Lossless backup of the whole box: pick a location via the system file
    // picker, then write the JSON the ViewModel built on the IO dispatcher.
    val context = LocalContext.current
    var exportReadyJson by remember { mutableStateOf<String?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = exportReadyJson
            exportReadyJson = null
            if (uri == null || json == null) return@rememberLauncherForActivityResult
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            Toast.makeText(
                context,
                if (ok) "Box exported" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }

    if (writing) {
        ManuscriptEditor(
            cards = writeCards,
            onClose = { writing = false }
        )
        return
    }

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
                    IconButton(onClick = { searching = !searching; viewMode = "shelf"; if (!searching) searchQuery = "" }) {
                        Icon(
                            if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                            contentDescription = "Search"
                        )
                    }
                    IconButton(onClick = { exportReadyJson = null; vm.exportJson { json -> exportReadyJson = json; exportLauncher.launch("pagetime-lumen-box.json") } }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Export box backup")
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
                    IconButton(onClick = {
                        writing = true
                        writeCards = null
                        vm.allCards { writeCards = it }
                    }) {
                        Icon(Icons.Outlined.EditNote, contentDescription = "Write from the box")
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
                boxes = boxes,
                selected = selectedBox,
                dueCount = dueCount,
                onSelect = { vm.selectBox(it) }
            )
            if (!searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = viewMode == "shelf",
                        onClick = { viewMode = "shelf" },
                        label = { Text("Shelf") }
                    )
                    FilterChip(
                        selected = viewMode == "map",
                        onClick = { viewMode = "map" },
                        label = { Text("Map") }
                    )
                    FilterChip(
                        selected = viewMode == "graph",
                        onClick = { viewMode = "graph" },
                        label = { Text("Graph") }
                    )
                }
            }
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
            val visibleCards = remember(shelfCards, searchQuery) {
                LumenSearch.filter(shelfCards, searchQuery)
            }
            // Branch depth per card, hoisted into one map. Computing it inside the
            // row did O(n) work per visible slip on every scroll frame (each row
            // re-called visibleCards.hashCode() across the whole list); a single
            // remembered pass is O(n) total, computed once per box/view change.
            val branchDepths = remember(visibleCards) {
                visibleCards.associate { card ->
                    card.id to LumenAddress.branchDepth(card.indexNumber, visibleCards)
                }
            }
            if (viewMode == "graph" && visibleCards.isNotEmpty()) {
                BoxGraphView(
                    cards = visibleCards,
                    onOpen = { detailCard = it }
                )
            } else if (viewMode == "map" && visibleCards.isNotEmpty()) {
                // The Inhaltsübersicht: the box as a tree of trunk lines and
                // branches, like the official archive's content overview.
                val roots = remember(shelfCards) { LumenTree.build(shelfCards) }
                BoxMapView(
                    roots = roots,
                    onOpen = { detailCard = it },
                    onEdit = { editing = it }
                )
            } else if (visibleCards.isEmpty()) {
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
                        // Reveal the branch tree: slips filed behind a card sit
                        // indented under it, so the box reads as lines of thought
                        // (21 → 21a → 21a1) instead of a flat 1,2,3 count.
                        val depth = branchDepths[card.id] ?: 0
                        LumenCardRow(
                            card = card,
                            indentDp = (depth * 14).dp,
                            isBranch = depth > 0,
                            threadLabel = threadLabels[card.id]?.let { (address, title) ->
                                "$address · $title"
                            },
                            onOpen = { detailCard = card },
                            onDelete = { deleting = card }
                        )
                    }
                }
            }
        }
    }

    detailCard?.let { card ->
        // The archive's slip view walks the line slip by slip (‹ ›) and shows
        // the branch the slip sits on; both come from the same shelf order.
        val indexInShelf = shelfCards.indexOfFirst { it.id == card.id }
        val previous = if (indexInShelf > 0) shelfCards[indexInShelf - 1] else null
        val next = if (indexInShelf in 0 until shelfCards.size - 1) shelfCards[indexInShelf + 1] else null
        val threadPath = remember(card.id, cards) {
            LumenAddress.threadPath(card.indexNumber, cards).map { it.first }
        }
        CardDetailDialog(
            card = card,
            previous = previous,
            next = next,
            onPrevious = { if (previous != null) detailCard = previous },
            onNext = { if (next != null) detailCard = next },
            threadPath = threadPath,
            boxCards = cards,
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
                runWithHelp(LumenOnboarding.Action.LINK) { linking = card; detailCard = null }
            },
            onFindConnections = {
                runWithHelp(LumenOnboarding.Action.CONNECT) { findingConnections = card; detailCard = null }
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
                runWithHelp(LumenOnboarding.Action.FILE_BEHIND) { filingBehind = card; detailCard = null }
            },
            onDelete = {
                deleting = card
                detailCard = null
            },
            onPullThread = {
                runWithHelp(LumenOnboarding.Action.PULL_THREAD) { pullingThread = card; detailCard = null }
            },
            onOpenSource = {
                detailCard = null
                onOpenSource(card.bookId)
            },
            onOpenLinked = { linked ->
                detailCard = linked
            },
            loadLinked = { c, cb -> vm.linkedCards(c, cb) },
            // Unlinked mentions: other slips that already quote this card's
            // title (and slips whose titles this card quotes) but were never
            // linked. One tap turns the echo into a real Verweisung.
            mentionSuggestions = remember(card.id, cards) {
                LumenMentions.pointingAt(card, cards, limit = 3) +
                    LumenMentions.madeBy(card, cards, limit = 3)
            },
            onLinkMention = { mention ->
                vm.link(mention.source.id, mention.target.id)
            }
        )
    }

    // Newcomer help: before a confusing action runs, explain it and ask for
    // confirmation. Turned off from Settings once the user has learned the box.
    helpPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { helpPrompt = null },
            title = { Text("${prompt.action.title} — what it does") },
            text = {
                Column {
                    Text(prompt.action.what)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing is changed yet. You'll get the chance to back out after this too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val go = prompt.onConfirm
                    helpPrompt = null
                    go()
                }) { Text(prompt.action.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { helpPrompt = null }) { Text("Not now") }
            }
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
            sortByRecency = true,
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
            canMove = boxes.size > 1,
            onFileBehind = {
                runWithHelp(LumenOnboarding.Action.FILE_BEHIND) { moreActions = null; filingBehind = card }
            },
            onPullThread = {
                runWithHelp(LumenOnboarding.Action.PULL_THREAD) { moreActions = null; pullingThread = card }
            },
            onToggleHub = { moreActions = null; vm.setHub(card.id, !card.isHub) },
            onMove = { moreActions = null; moving = card },
            onDelete = { moreActions = null; deleting = card },
            onDismiss = { moreActions = null }
        )
    }

    moving?.let { card ->
        MoveBoxDialog(
            card = card,
            boxes = boxes,
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
            onDismiss = { studying = false }
        )
    }

    if (register) {
        RegisterDialog(
            cards = cards,
            hubs = hubs,
            onOpenCard = { card ->
                register = false
                detailCard = card
            },
            onOpenHub = { hub ->
                register = false
                structureMapHub = hub
            },
            onDismiss = { register = false }
        )
    }

    structureMapHub?.let { hub ->
        StructureMapDialog(
            hub = hub,
            onLoad = { callback -> vm.allCards(callback) },
            onDismiss = { structureMapHub = null }
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
    boxes: List<Int>,
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
        // Only the boxes that actually contain cards. Captures always file into
        // Box 1, so an untouched box never appears — everything stays in one box.
        boxes.forEach { box ->
            FilterChip(
                selected = selected == box,
                onClick = { onSelect(box) },
                label = { Text("Box $box") }
            )
        }
        if (dueCount > 0) {
            FilterChip(
                selected = false,
                onClick = { onSelect(boxes.first()) },
                label = { Text("$dueCount due") }
            )
        }
    }
}

@Composable
private fun LumenCardRow(
    card: LumenCardEntity,
    indentDp: androidx.compose.ui.unit.Dp = 0.dp,
    isBranch: Boolean = false,
    threadLabel: String? = null,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBranch) {
                    Text(
                        "›",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(12.dp)
                    )
                }
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
                if (card.isHub) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "HUB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
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

            if (threadLabel != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    threadLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

/**
 * The Inhaltsübersicht: the box as a tree of trunk lines and branches, like
 * the official archive's content overview. Trunks carry their title (the
 * front of the first slip, e.g. "21 Literatur") and their size; branches
 * expand in place, and tapping a slip opens it.
 */
@Composable
private fun BoxMapView(
    roots: List<LumenTree.Node>,
    onOpen: (LumenCardEntity) -> Unit,
    onEdit: (LumenCardEntity) -> Unit
) {
    val expanded = remember { mutableStateOf(mutableSetOf<String>()) }
    val rows = remember(roots, expanded.value) {
        val visible = mutableListOf<Pair<LumenTree.Node, Int>>()
        fun walk(node: LumenTree.Node, depth: Int) {
            visible.add(node to depth)
            if (node.card.id in expanded.value) {
                node.children.forEach { walk(it, depth + 1) }
            }
        }
        roots.forEach { walk(it, 0) }
        visible
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Trunk lines (1, 2, 3…) are threads — the first slip's title names the line. Edit a trunk to rename it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(rows, key = { it.first.card.id }) { (node, depth) ->
                val isExpanded = node.card.id in expanded.value
                MapNodeRow(
                    node = node,
                    depth = depth,
                    expanded = isExpanded,
                    onToggle = {
                        if (isExpanded) expanded.value.remove(node.card.id)
                        else expanded.value.add(node.card.id)
                    },
                    onOpen = { onOpen(node.card) },
                    onEdit = { onEdit(node.card) }
                )
            }
        }
    }
}

/**
 * The box as a web: every slip a node, explicit links solid, the filing
 * hierarchy dotted, hubs drawn large so clusters of thought stand out. Tap a
 * node to open the slip; pinch to zoom, drag to pan. Layout is a deterministic
 * force simulation ([LumenGraphLayout]) run off the main thread.
 */
@Composable
private fun BoxGraphView(
    cards: List<LumenCardEntity>,
    onOpen: (LumenCardEntity) -> Unit
) {
    val graph = remember(cards) { LumenGraph.build(cards) }
    val (nodes, edges) = graph
    var layout by remember(cards) { mutableStateOf<LumenGraphLayout.Result?>(null) }
    androidx.compose.runtime.LaunchedEffect(cards) {
        layout = withContext(kotlinx.coroutines.Dispatchers.Default) {
            LumenGraphLayout.run(nodes, edges)
        }
    }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val cardById = remember(cards) { cards.associateBy { it.id } }

    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tappedId by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val hubRadius = with(density) { 16.dp.toPx() }
    val nodeRadius = with(density) { 9.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    // Colors must be captured outside the Canvas draw lambda, which is not a
    // composable context and cannot read MaterialTheme itself.
    val colors = MaterialTheme.colorScheme
    val labelStyle = TextStyle(fontSize = 9.sp, color = colors.onSurfaceVariant)

    Column(Modifier.fillMaxSize()) {
        val result = layout
        if (result == null || result.positions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Text(
                if (result.shownCount < result.totalCount) {
                    "Web of the box — showing ${result.shownCount} of ${result.totalCount} slips (hubs first). " +
                        "Tap a dot to open the slip."
                } else {
                    "Web of the box — tap a dot to open the slip. Pinch to zoom, drag to pan. " +
                        "Big dots are hub notes."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(cards) {
                        detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(0.5f, 4f)
                            pan += gesturePan
                        }
                    }
                    .pointerInput(cards) {
                        detectTapGestures { tap ->
                            val size = canvasSize
                            val positions = layout?.positions ?: return@detectTapGestures
                            if (size.width == 0 || size.height == 0) return@detectTapGestures
                            val scale = minOf(size.width.toFloat(), size.height.toFloat()) * zoom
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val hit = positions.entries.firstOrNull { (id, p) ->
                                val node = byId[id] ?: return@firstOrNull false
                                val r = if (node.isHub) hubRadius else nodeRadius
                                val sx = (p.first - 0.5f) * scale + center.x + pan.x
                                val sy = (p.second - 0.5f) * scale + center.y + pan.y
                                (tap.x - sx) * (tap.x - sx) + (tap.y - sy) * (tap.y - sy) <= r * r * 4
                            }
                            if (hit != null) {
                                tappedId = hit.key
                                cardById[hit.key]?.let(onOpen)
                            }
                        }
                    }
            ) {
                val positions = layout?.positions ?: return@Canvas
                val scale = minOf(size.width, size.height) * zoom
                val center = Offset(size.width / 2f, size.height / 2f)
                fun toScreen(p: Pair<Float, Float>): Offset =
                    Offset((p.first - 0.5f) * scale, (p.second - 0.5f) * scale) + center + pan

                // Edges first, so nodes sit on top.
                edges.forEach { edge ->
                    val a = positions[edge.fromId] ?: return@forEach
                    val b = positions[edge.toId] ?: return@forEach
                    val from = toScreen(a)
                    val to = toScreen(b)
                    if (edge.kind == LumenGraphEdgeKind.LINK) {
                        drawLine(
                            color = colors.primary.copy(alpha = 0.5f),
                            start = from,
                            end = to,
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    } else {
                        drawLine(
                            color = colors.onSurfaceVariant.copy(alpha = 0.22f),
                            start = from,
                            end = to,
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                    }
                }
                positions.forEach { (id, p) ->
                    val node = byId[id] ?: return@forEach
                    val screen = toScreen(p)
                    if (node.isHub) {
                        drawCircle(
                            color = colors.tertiary,
                            radius = hubRadius,
                            center = screen
                        )
                    } else {
                        drawCircle(
                            color = colors.primaryContainer,
                            radius = nodeRadius,
                            center = screen
                        )
                        drawCircle(
                            color = colors.primary.copy(alpha = 0.6f),
                            radius = nodeRadius,
                            center = screen,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    if (node.isHub || nodes.size <= 80) {
                        val label = if (node.address.length > 12) node.address.take(11) + "…" else node.address
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            topLeft = Offset(screen.x + nodeRadius + 2.dp.toPx(), screen.y - 6.dp.toPx()),
                            style = labelStyle
                        )
                    }
                }
                // Lift the most recently tapped node, so the tap target is visible.
                tappedId?.let { id ->
                    val p = positions[id] ?: return@let
                    val node = byId[id] ?: return@let
                    val screen = toScreen(p)
                    drawCircle(
                        color = colors.primary,
                        radius = (if (node.isHub) hubRadius else nodeRadius) + 4.dp.toPx(),
                        center = screen,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}

/** One row of the map: chevron, address, title, size, and a rename pencil. */
@Composable
private fun MapNodeRow(
    node: LumenTree.Node,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.children.isEmpty()) {
            Spacer(Modifier.size(40.dp))
        } else {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse branch" else "Expand branch",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            node.card.indexNumber.ifBlank { "?" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(onClick = onOpen)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            node.card.front,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
        )
        if (node.descendantCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "${node.descendantCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Edit ${node.card.indexNumber.ifBlank { "?" }}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CardDetailDialog(
    card: LumenCardEntity,
    previous: LumenCardEntity? = null,
    next: LumenCardEntity? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    threadPath: List<String> = emptyList(),
    boxCards: List<LumenCardEntity> = emptyList(),
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
    loadLinked: (LumenCardEntity, (List<LumenCardEntity>) -> Unit) -> Unit,
    mentionSuggestions: List<LumenMentions.Mention> = emptyList(),
    onLinkMention: (LumenMentions.Mention) -> Unit = {}
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
                    IconButton(onClick = onPrevious, enabled = previous != null, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = "Previous slip",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        card.indexNumber.ifBlank { "?" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onNext, enabled = next != null, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "Next slip",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
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
                // The branch the slip sits on — the archive's branch visualization.
                if (threadPath.size > 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        threadPath.joinToString(" → "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
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
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "THE IDEA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        ReferencedText(
                            text = card.front,
                            boxCards = boxCards,
                            style = MaterialTheme.typography.titleLarge,
                            onOpenCard = onOpenLinked
                        )
                        if (card.back.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            ReferencedText(
                                text = card.back,
                                boxCards = boxCards,
                                style = MaterialTheme.typography.bodyLarge,
                                onOpenCard = onOpenLinked
                            )
                        }
                    }
                }
                if (card.quote.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
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
                    Text(
                        "REGISTER TERMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        card.keywords,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                if (mentionSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Possible connections",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "Slips that already echo each other in the text but were never linked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    mentionSuggestions.forEach { mention ->
                        val pointsAtMe = mention.target.id == card.id
                        val other = if (pointsAtMe) mention.source else mention.target
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${other.indexNumber.ifBlank { "?" }} — ${other.front}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (pointsAtMe) "quotes your title" else "your title quotes it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onLinkMention(mention) },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Link")
                            }
                        }
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
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "${formatDate(snippet.addedAt)} — ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ReferencedText(
                                text = snippet.text,
                                boxCards = boxCards,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                onOpenCard = onOpenLinked
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

/**
 * Text with Luhmann's Verweisungen made tappable: address-shaped tokens that
 * resolve to a real card are rendered as underlined links that open that card.
 * Everything else renders exactly as written. (Clickable spans are handled
 * manually — this Compose version predates LinkAnnotation — by tagging each
 * mention with a string annotation and hit-testing the tap position.)
 */
@Composable
private fun ReferencedText(
    text: String,
    boxCards: List<LumenCardEntity>,
    style: TextStyle,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    onOpenCard: (LumenCardEntity) -> Unit
) {
    val byId = remember(boxCards.hashCode()) { boxCards.associateBy { it.id } }
    val mentions = remember(text, boxCards.hashCode()) { LumenReferences.find(text, boxCards) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, mentions, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            for (mention in mentions) {
                append(text.substring(cursor, mention.start))
                pushStringAnnotation(REFERENCE_TAG, mention.cardId)
                withStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                ) {
                    append(text.substring(mention.start, mention.end))
                }
                cursor = mention.end
            }
            append(text.substring(cursor))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        onTextLayout = { layout = it },
        modifier = Modifier
            .clipToBounds()
            .pointerInput(annotated) {
                detectTapGestures { tap ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(tap)
                    val cardId = annotated.getStringAnnotations(REFERENCE_TAG, offset, offset)
                        .firstOrNull()?.item
                        ?: annotated.getStringAnnotations(REFERENCE_TAG, (offset - 1).coerceAtLeast(0), offset)
                            .firstOrNull()?.item
                    cardId?.let { byId[it] }?.let(onOpenCard)
                }
            }
    )
}

private const val REFERENCE_TAG = "lumen_reference"

@Composable
private fun CardActionsDialog(
    card: LumenCardEntity,
    canMove: Boolean = false,
    onFileBehind: () -> Unit,
    onPullThread: () -> Unit,
    onToggleHub: () -> Unit,
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
                TextButton(
                    onClick = onToggleHub,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (card.isHub) "Remove hub marker" else "Mark as hub note", maxLines = 1) }
                if (canMove) {
                    TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) { Text("Move to another box", maxLines = 1) }
                }
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
    title: String = "Link \u201C${card.indexNumber}\u201D to…",
    sortByRecency: Boolean = false
) {
    val existing = remember(card.linksJson) { LumenCapture.linksFromJson(card.linksJson) }
    val candidates = if (sortByRecency) {
        // Filing is a thought connection: the notes you recently wrote are the
        // ones this idea continues, so they lead the picker.
        allCards
            .filter { it.id != card.id }
            .sortedWith(
                compareByDescending<LumenCardEntity> { it.updatedAt }.thenBy { it.indexNumber }
            )
    } else {
        allCards
            .filter { it.id != card.id }
            .sortedWith(compareBy<LumenCardEntity> { it.box }.thenBy { it.indexNumber })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (sortByRecency) {
                Column {
                    Text(title)
                    Text(
                        "Pick the note this thought continues — it gets the next address in that branch. Recently touched first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(title)
            }
        },
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
    boxes: List<Int>,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to box") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                boxes.forEach { box ->
                    FilterChip(
                        selected = box == card.box,
                        onClick = { onMove(box) },
                        label = { Text("Box $box") }
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
    hubs: List<LumenCardEntity> = emptyList(),
    onOpenCard: (LumenCardEntity) -> Unit,
    onOpenHub: (LumenCardEntity) -> Unit = {},
    onDismiss: () -> Unit
) {
    val entries = remember(cards.hashCode()) { LumenRegister.build(cards) }
    val byId = remember(cards.hashCode()) { cards.associateBy { it.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register") },
        text = {
            if (entries.isEmpty() && hubs.isEmpty()) {
                Text(
                    "The register grows by itself as you capture cards — every keyword gets an entry pointing at its address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    // The main index points to hub notes first — Luhmann's
                    // index walked you to 10–20 structure maps, not thousands
                    // of topics.
                    if (hubs.isNotEmpty()) {
                        item(key = "hub-header") {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "HUB NOTES — STRUCTURE MAPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Your index points here: tap a hub to walk into its cluster.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(hubs, key = { it.id }) { hub ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHub(hub) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    hub.indexNumber.ifBlank { "?" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    hub.front,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        item(key = "hub-divider") {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
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

/**
 * A structure map: the hub note at the top, then each cluster it links to —
 * every starting point expanded into its whole line in shelf order. The hub
 * is a mini table of contents; the map walks you into the web of ideas.
 */
@Composable
private fun StructureMapDialog(
    hub: LumenCardEntity,
    onLoad: ((List<LumenCardEntity>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var all by remember(hub.id) { mutableStateOf<List<LumenCardEntity>?>(null) }
    androidx.compose.runtime.LaunchedEffect(hub.id) { onLoad { all = it } }
    val clusters = remember(hub.id, all?.hashCode()) {
        all?.let { LumenStructureMap.clusters(hub, it) } ?: emptyList()
    }
    val rendered = remember(hub.id, clusters.hashCode()) {
        LumenStructureMap.render(hub, clusters)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Structure map — ${hub.indexNumber.ifBlank { "?" }}")
                }
                Text(
                    "${clusters.size} cluster${if (clusters.size == 1) "" else "s"} linked from this hub note",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (all == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column {
                    if (clusters.isEmpty()) {
                        Text(
                            "This hub has no starting points yet — open its details and Link it to the cards where each cluster begins.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            rendered,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 360.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (clusters.isNotEmpty()) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Lumen structure map", rendered)
                        )
                        copied = true
                    }
                ) { Text(if (copied) "Copied" else "Copy outline") }
            }
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

/**
 * The writing desk — Luhmann's way of producing something new: ask the box a
 * question, take out the slips that answer (whole lines, not single Zetteln),
 * order them into an argument, then write from that arrangement. Everything
 * here is local and deterministic; the copy button hands you the outline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManuscriptEditor(
    cards: List<LumenCardEntity>?,
    onClose: () -> Unit
) {
    if (cards == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var question by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ManuscriptEntry>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val suggestions = remember(question, cards) {
        if (question.trim().length < 3) emptyList() else LumenManuscript.gather(question, cards)
    }
    val addedIds = remember(entries) { entries.map { it.card.id }.toSet() }

    val copyManuscript = {
        val text = LumenManuscript.render(question, entries, draft)
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("Lumen manuscript", text)
        )
        copied = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Write — from the box") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = copyManuscript) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy manuscript")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "Luhmann wrote by asking the box a question and arranging the slips it returned. Ask, gather, order the argument, then write.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("What are you writing about?") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "THE BOX ANSWERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            when {
                question.trim().length < 3 -> Text(
                    "Ask a question — the box returns the cards that speak to it, whole lines included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                suggestions.isEmpty() -> Text(
                    "Nothing matches yet. Try different words, or open the register to see the terms your box actually uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> suggestions.forEach { card ->
                    if (card.id !in addedIds) {
                        Surface(
                            onClick = { entries = entries + ManuscriptEntry(card) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        card.indexNumber.ifBlank { "?" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        card.front,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "Add to argument",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "YOUR ARGUMENT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Text(
                    "Tap a suggestion above to bring it in, then order the slips the way the argument should run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    ManuscriptEntryRow(
                        entry = entry,
                        canMoveUp = index > 0,
                        canMoveDown = index < entries.size - 1,
                        onMoveUp = {
                            val list = entries.toMutableList()
                            val tmp = list[index]
                            list[index] = list[index - 1]
                            list[index - 1] = tmp
                            entries = list
                        },
                        onMoveDown = {
                            val list = entries.toMutableList()
                            val tmp = list[index]
                            list[index] = list[index + 1]
                            list[index + 1] = tmp
                            entries = list
                        },
                        onRemove = { entries = entries.filterNot { it.card.id == entry.card.id } },
                        onRole = { role ->
                            entries = entries.mapIndexed { i, e -> if (i == index) e.copy(role = role) else e }
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "YOUR DRAFT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Write here — the slips above are your outline") },
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = copyManuscript,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied manuscript" else "Copy manuscript")
            }
        }
    }
}

/** One slip inside the manuscript: address, front, role, and ordering controls. */
@Composable
private fun ManuscriptEntryRow(
    entry: ManuscriptEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onRole: (LumenRole?) -> Unit
) {
    var roleMenu by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.card.indexNumber.ifBlank { "?" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.card.front,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Move down",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove from argument",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (entry.card.back.isNotBlank()) {
                Text(
                    entry.card.back,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                TextButton(onClick = { roleMenu = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        entry.role?.let { "Role: ${it.label}" } ?: "Assign a role",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("No role") },
                        onClick = { onRole(null); roleMenu = false }
                    )
                    LumenRole.entries.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.label) },
                            onClick = { onRole(role); roleMenu = false }
                        )
                    }
                }
            }
        }
    }
}
