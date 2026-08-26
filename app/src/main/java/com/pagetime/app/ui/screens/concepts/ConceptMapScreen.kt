package com.pagetime.app.ui.screens.concepts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipEntity

private enum class MapMode { Ideas, Connections }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConceptMapScreen(
    onBack: () -> Unit,
    initialBookId: String? = null,
    viewModel: ConceptMapViewModel = viewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val selectedBookId by viewModel.selectedBookId.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val selectedConceptId by viewModel.selectedConceptId.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var mode by remember { mutableStateOf(MapMode.Ideas) }
    var trail by remember(selectedBookId) { mutableStateOf(emptyList<String>()) }

    val orderedConcepts = remember(map) { ConceptMapNavigation.orderedConcepts(map) }
    val activeConceptId = selectedConceptId?.takeIf { id -> map.concepts.any { it.id == id } }
        ?: orderedConcepts.firstOrNull()?.id
    val visibleTrail = remember(trail, activeConceptId) {
        trail.ifEmpty { activeConceptId?.let(::listOf).orEmpty() }
    }

    fun selectAndExpand(id: String) {
        if (id.isBlank()) return
        trail = if (id in trail) {
            trail.take((trail.indexOf(id) + 1).coerceAtLeast(1))
        } else {
            trail + id
        }
        viewModel.selectConcept(id)
    }

    LaunchedEffect(initialBookId, books) {
        initialBookId?.let { bookId ->
            if (books.any { it.id == bookId } && selectedBookId != bookId) {
                viewModel.selectBook(bookId)
            }
        }
    }

    LaunchedEffect(selectedBookId) {
        trail = emptyList()
        viewModel.selectConcept(null)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Concept map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::generateNow, enabled = !generating) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Update map")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (books.isEmpty()) {
            EmptyMap(Modifier.fillMaxSize().padding(padding))
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BookPicker(books, selectedBookId, viewModel::selectBook)
                Text(
                    if (generating) "Reading the latest passage…" else "${map.concepts.size} ideas · ${map.relationships.size} connections",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == MapMode.Ideas, onClick = { mode = MapMode.Ideas }, label = { Text("Explore ideas") })
                    FilterChip(selected = mode == MapMode.Connections, onClick = { mode = MapMode.Connections }, label = { Text("See connections") })
                }
                if (map.concepts.isEmpty()) {
                    EmptyMap(Modifier.fillMaxWidth().weight(1f), showAction = true, onGenerate = viewModel::generateNow)
                } else if (mode == MapMode.Ideas) {
                    GuidedIdeas(
                        map = map,
                        orderedConcepts = orderedConcepts,
                        activeConceptId = activeConceptId,
                        trail = visibleTrail,
                        onSelect = ::selectAndExpand,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    ConnectionsList(
                        map = map,
                        onSelect = ::selectAndExpand,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BookPicker(books: List<BookEntity>, selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = books.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Reading map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected?.title ?: "Choose a book", modifier = Modifier.padding(16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    books.forEach { book ->
                        Surface(onClick = { onSelect(book.id); expanded = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(book.title, modifier = Modifier.padding(16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A linear entry point into the graph, with one hop of related concepts at a time. */
@Composable
private fun GuidedIdeas(
    map: ConceptMap,
    orderedConcepts: List<ConceptEntity>,
    activeConceptId: String?,
    trail: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier
) {
    val active = map.concepts.firstOrNull { it.id == activeConceptId }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Start with the strongest idea, then follow the author’s relationships.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orderedConcepts, key = { it.id }) { concept ->
                    ConceptRailCard(
                        concept = concept,
                        selected = concept.id == activeConceptId,
                        number = orderedConcepts.indexOf(concept) + 1,
                        onClick = { onSelect(concept.id) }
                    )
                }
            }
        }
        if (active != null) {
            item {
                ConceptFocusCard(
                    concept = active,
                    map = map,
                    trail = trail,
                    onSelect = onSelect
                )
            }
        }
    }
}

@Composable
private fun ConceptRailCard(concept: ConceptEntity, selected: Boolean, number: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(156.dp)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(concept.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${concept.mentionCount} mention${if (concept.mentionCount == 1) "" else "s"} · ${concept.type}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConceptFocusCard(
    concept: ConceptEntity,
    map: ConceptMap,
    trail: List<String>,
    onSelect: (String) -> Unit
) {
    val related = ConceptMapNavigation.relatedConcepts(map, concept.id)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (trail.size <= 1) "Start here" else "Following the chain · step ${trail.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(concept.label, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${concept.type} · ${concept.mentionCount} reading context${if (concept.mentionCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Filled.AutoGraph, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Text(concept.description, style = MaterialTheme.typography.bodyLarge)
            concept.sourceQuote?.let {
                Text("From the reading: “$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (related.isEmpty()) {
                Text("No connected idea yet. Keep reading to grow this branch.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("What is this connected to?", style = MaterialTheme.typography.titleMedium)
                related.take(8).forEach { item ->
                    Surface(
                        onClick = { onSelect(item.concept.id) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    if (item.isForward) "${item.relationship.relationType} → ${item.concept.label}" else "${item.concept.label} → ${item.relationship.relationType}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(item.relationship.explanation, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = "Follow connection", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionsList(map: ConceptMap, onSelect: (String) -> Unit, modifier: Modifier) {
    val conceptsById = remember(map.concepts) { map.concepts.associateBy { it.id } }
    val relationships = remember(map) {
        map.relationships.sortedWith(
            compareBy<ConceptRelationshipEntity> { it.firstChapterIndex }
                .thenByDescending { it.confidence }
                .thenBy { it.relationType.lowercase() }
        )
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Each arrow has meaning. Tap either concept to explore from there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(relationships, key = { it.id }) { edge ->
            val source = conceptsById[edge.sourceConceptId]
            val target = conceptsById[edge.targetConceptId]
            if (source != null && target != null) {
                ConnectionRow(source, target, edge, onSelect)
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    source: ConceptEntity,
    target: ConceptEntity,
    edge: ConceptRelationshipEntity,
    onSelect: (String) -> Unit
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(source.id) }, modifier = Modifier.weight(1f)) {
                    Text(source.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("→", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onSelect(target.id) }, modifier = Modifier.weight(1f)) {
                    Text(target.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(edge.relationType, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(edge.explanation, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyMap(modifier: Modifier, showAction: Boolean = false, onGenerate: () -> Unit = {}) {
    Column(modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.AutoGraph, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Your book’s argument will appear here", style = MaterialTheme.typography.titleLarge)
        Text("Keep reading and PageTime will add concepts and explain how they connect.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (showAction) Button(onClick = onGenerate, modifier = Modifier.padding(top = 20.dp)) { Text("Build from latest reading") }
    }
}
