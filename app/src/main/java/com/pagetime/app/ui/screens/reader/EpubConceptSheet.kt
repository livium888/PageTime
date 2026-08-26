package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubConceptSheet(
    concept: ConceptEntity,
    relationships: List<ConceptRelationshipEntity>,
    concepts: List<ConceptEntity>,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit
) {
    val conceptById = concepts.associateBy { it.id }
    val connections = relationships
        .filter { it.sourceConceptId == concept.id || it.targetConceptId == concept.id }
        .sortedWith(compareByDescending<ConceptRelationshipEntity> { it.confidence }.thenBy { it.relationType })
        .take(4)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Concept encountered", style = MaterialTheme.typography.labelLarge)
            Text(
                concept.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                concept.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (connections.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Connected ideas", style = MaterialTheme.typography.titleMedium)
                connections.forEach { edge ->
                    val otherId = if (edge.sourceConceptId == concept.id) {
                        edge.targetConceptId
                    } else {
                        edge.sourceConceptId
                    }
                    val other = conceptById[otherId]
                    if (other != null) {
                        Text(
                            "${edge.relationType} · ${other.label}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            concept.sourceQuote?.takeIf { it.isNotBlank() }?.let { quote ->
                Text(
                    "“${quote.trim()}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                    Text("Open concept map")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
