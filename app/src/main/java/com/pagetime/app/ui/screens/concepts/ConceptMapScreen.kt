package com.pagetime.app.ui.screens.concepts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.data.ConceptMap
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipEntity
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConceptMapScreen(
    onBack: () -> Unit,
    viewModel: ConceptMapViewModel = viewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val selectedBookId by viewModel.selectedBookId.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val selectedConceptId by viewModel.selectedConceptId.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var bookMenuExpanded by remember { mutableStateOf(false) }

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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box {
                    val selected = books.firstOrNull { it.id == selectedBookId }
                    OutlinedButton(onClick = { bookMenuExpanded = true }) {
                        Text(selected?.title ?: "Choose a book")
                    }
                    DropdownMenu(
                        expanded = bookMenuExpanded,
                        onDismissRequest = { bookMenuExpanded = false }
                    ) {
                        books.forEach { book ->
                            DropdownMenuItem(
                                text = { Text(book.title) },
                                onClick = {
                                    viewModel.selectBook(book.id)
                                    bookMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    if (generating) "Reading the latest passage…" else "${map.concepts.size} concepts · ${map.relationships.size} typed connections",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (map.concepts.isEmpty()) {
                    EmptyMap(Modifier.fillMaxWidth().weight(1f), showAction = true, onGenerate = viewModel::generateNow)
                } else {
                    ConceptGraph(
                        map = map,
                        selectedConceptId = selectedConceptId,
                        onSelect = viewModel::selectConcept,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                    selectedConceptId?.let { id ->
                        map.concepts.firstOrNull { it.id == id }?.let { concept ->
                            ConceptDetail(
                                concept = concept,
                                relationships = map.relationships,
                                concepts = map.concepts,
                                onClose = { viewModel.selectConcept(null) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConceptGraph(
    map: ConceptMap,
    selectedConceptId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier
) {
    var pan by remember { mutableStateOf(Offset.Zero) }
    val positions = remember(map.concepts) {
        map.concepts.mapIndexed { index, concept ->
            val angle = (index.toFloat() / map.concepts.size.coerceAtLeast(1)) * (2f * Math.PI).toFloat()
            concept.id to Offset(0.5f + 0.34f * cos(angle), 0.5f + 0.34f * sin(angle))
        }.toMap()
    }
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .pointerInput(map.concepts) {
                detectDragGestures { change, drag ->
                    change.consume()
                    pan += drag
                }
            }
            .pointerInput(map.concepts) {
                detectTapGestures { tap ->
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val hit = positions.entries.firstOrNull { (_, position) ->
                        val center = Offset(position.x * width, position.y * height) + pan
                        (tap - center).getDistance() < 46.dp.toPx()
                    }
                    onSelect(hit?.key)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val width = size.width
            val height = size.height
            val points = positions.mapValues { (_, position) ->
                Offset(position.x * width, position.y * height) + pan
            }
            map.relationships.forEach { relationship ->
                val start = points[relationship.sourceConceptId] ?: return@forEach
                val end = points[relationship.targetConceptId] ?: return@forEach
                drawLine(
                    color = if (relationship.confidence >= 0.75f) Color(0xFF0F766E) else Color(0xFF9AA4AE),
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx()
                )
            }
            map.concepts.forEach { concept ->
                val center = points[concept.id] ?: return@forEach
                drawCircle(
                    color = if (concept.id == selectedConceptId) Color(0xFF0F766E) else Color(0xFFD4F0EA),
                    radius = if (concept.id == selectedConceptId) 38.dp.toPx() else 32.dp.toPx(),
                    center = center
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Tap a node to inspect it · drag to explore", style = MaterialTheme.typography.labelSmall)
        }
        positions.forEach { (id, position) ->
            val concept = map.concepts.firstOrNull { it.id == id } ?: return@forEach
            Surface(
                onClick = { onSelect(id) },
                shape = RoundedCornerShape(12.dp),
                color = if (id == selectedConceptId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (id == selectedConceptId) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (position.x * with(density) { 280.dp.toPx() }).toInt() + pan.x.toInt(),
                            (position.y * with(density) { 340.dp.toPx() }).toInt() + pan.y.toInt()
                        )
                    }
            ) {
                Text(
                    concept.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun ConceptDetail(
    concept: ConceptEntity,
    relationships: List<ConceptRelationshipEntity>,
    concepts: List<ConceptEntity>,
    onClose: () -> Unit
) {
    val connectedIds = relationships.filter {
        it.sourceConceptId == concept.id || it.targetConceptId == concept.id
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(concept.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${concept.type} · seen in ${concept.mentionCount} reading context${if (concept.mentionCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClose) { Text("×", style = MaterialTheme.typography.titleLarge) }
            }
            Text(concept.description, style = MaterialTheme.typography.bodyMedium)
            concept.sourceQuote?.let {
                Text("Source: \"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            connectedIds.take(6).forEach { edge ->
                val otherId = if (edge.sourceConceptId == concept.id) edge.targetConceptId else edge.sourceConceptId
                val other = concepts.firstOrNull { it.id == otherId }?.label ?: "another concept"
                Text(
                    if (edge.sourceConceptId == concept.id) "${edge.relationType} → $other" else "← ${edge.relationType} $other",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyMap(modifier: Modifier, showAction: Boolean = false, onGenerate: () -> Unit = {}) {
    Column(
        modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.AutoGraph, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Your book’s argument will appear here", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Keep reading and PageTime will add concepts and explain how they connect. This is a relational map, not a chapter outline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (showAction) {
            Button(onClick = onGenerate, modifier = Modifier.padding(top = 20.dp)) { Text("Build from latest reading") }
        }
    }
}
