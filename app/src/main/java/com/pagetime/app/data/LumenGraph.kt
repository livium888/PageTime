package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One node in the box graph — a slip, with the metadata the view needs. */
data class LumenGraphNode(
    val id: String,
    val address: String,
    val label: String,
    val isHub: Boolean,
    val box: Int,
    /** Number of explicit links + filed-behind parent edges touching this slip. */
    val degree: Int,
)

/** One edge in the box graph. */
data class LumenGraphEdge(
    val fromId: String,
    val toId: String,
    val kind: LumenGraphEdgeKind,
)

enum class LumenGraphEdgeKind { LINK, PARENT }

/**
 * The slip box as a graph. Every slip is a node; two kinds of edges connect
 * them:
 *  - [LumenGraphEdgeKind.LINK] — explicit cross-references (Luhmann's
 *    Verweisungen, both directions become one undirected edge);
 *  - [LumenGraphEdgeKind.PARENT] — the filing hierarchy (21a1 filed behind
 *    21a), drawn faintly so the tree structure stays visible behind the web.
 *
 * The layout is deliberately a separate pure step ([LumenGraphLayout]) so the
 * graph itself is trivial to test: nodes = cards, edges = the actual links.
 */
object LumenGraph {
    fun build(cards: List<LumenCardEntity>): Pair<List<LumenGraphNode>, List<LumenGraphEdge>> {
        val cardById = cards.associateBy { it.id }

        val explicit = LinkedHashMap<Pair<String, String>, Unit>()
        val parent = LinkedHashMap<Pair<String, String>, Unit>()
        val degree = HashMap<String, Int>()

        fun bump(
            id: String,
            amount: Int = 1,
        ) {
            degree[id] = (degree[id] ?: 0) + amount
        }

        for (card in cards) {
            // Explicit links: one undirected edge, deduplicated by id pair.
            for (otherId in LumenCapture.linksFromJson(card.linksJson)) {
                if (otherId !in cardById || otherId == card.id) continue
                val pair = if (card.id < otherId) card.id to otherId else otherId to card.id
                if (explicit.put(pair, Unit) == null) {
                    bump(card.id)
                    bump(otherId)
                }
            }
            // Filing hierarchy: connect each slip to its direct parent address,
            // so the box's trunk lines stay visible as structure.
            val parentAddress =
                LumenAddress.threadPath(card.indexNumber, cards)
                    .dropLast(1)
                    .lastOrNull()
                    ?.first
            if (parentAddress != null) {
                val parentCard = cards.firstOrNull { it.indexNumber.trim() == parentAddress }
                if (parentCard != null && parentCard.id != card.id) {
                    val pair = if (card.id < parentCard.id) card.id to parentCard.id else parentCard.id to card.id
                    if (parent.put(pair, Unit) == null) {
                        bump(card.id)
                        bump(parentCard.id)
                    }
                }
            }
        }

        val nodes =
            cards.map { card ->
                LumenGraphNode(
                    id = card.id,
                    address = card.indexNumber.ifBlank { "?" },
                    label = card.front,
                    isHub = card.isHub,
                    box = card.box,
                    degree = degree[card.id] ?: 0,
                )
            }
        val edges =
            buildList {
                explicit.keys.forEach { (a, b) -> add(LumenGraphEdge(a, b, LumenGraphEdgeKind.LINK)) }
                parent.keys.forEach { (a, b) -> add(LumenGraphEdge(a, b, LumenGraphEdgeKind.PARENT)) }
            }
        return nodes to edges
    }
}

/**
 * Deterministic force-directed layout for the box graph. Repulsion pushes all
 * nodes apart, edges pull their endpoints together, a mild centering force
 * keeps the whole thing on the canvas, and fixed iterations + a deterministic
 * seed mean the same box always draws the same way. Runs off the main thread
 * for boxes too big to lay out inline.
 *
 * Very large boxes are capped: hubs and well-connected slips are kept (they
 * are the interesting structure), the rest is trimmed.
 */
object LumenGraphLayout {
    data class Result(
        /** id → normalized position in (0,1) × (0,1). */
        val positions: Map<String, Pair<Float, Float>>,
        val shownCount: Int,
        val totalCount: Int,
    )

    private const val ITERATIONS = 90
    private const val MAX_NODES = 250
    private const val REPULSION = 0.02f
    private const val ATTRACTION = 0.012f
    private const val CENTERING = 0.004f
    private const val COOLING = 0.96f

    fun run(
        nodes: List<LumenGraphNode>,
        edges: List<LumenGraphEdge>,
        maxNodes: Int = MAX_NODES,
    ): Result {
        if (nodes.isEmpty()) return Result(emptyMap(), 0, 0)
        val selected = selectNodes(nodes, maxNodes)
        val selectedIds = selected.map { it.id }.toSet()
        val keptEdges = edges.filter { it.fromId in selectedIds && it.toId in selectedIds }
        val byId = selected.associateBy { it.id }

        // Deterministic circle seed, indexed by node order.
        val positions =
            selected
                .mapIndexed { i, node ->
                    node.id to
                        Pair(
                            (cos(i * 2.399963f) + 1f) / 2f,
                            (sin(i * 2.399963f) + 1f) / 2f,
                        )
                }
                .toMap()
                .toMutableMap()

        var speed = 1f
        repeat(ITERATIONS) {
            val velocities = HashMap<String, Pair<Float, Float>>()
            val list = selectedIds.toList()
            for (i in list.indices) {
                val a = list[i]
                val (ax, ay) = positions.getValue(a)
                var fx = 0f
                var fy = 0f
                for (j in list.indices) {
                    if (i == j) continue
                    val (bx, by) = positions.getValue(list[j])
                    val dx = ax - bx
                    val dy = ay - by
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.02f)
                    fx += (dx / dist) * (REPULSION / (dist * dist))
                    fy += (dy / dist) * (REPULSION / (dist * dist))
                }
                velocities[a] = fx to fy
            }
            for (edge in keptEdges) {
                val (ax, ay) = positions.getValue(edge.fromId)
                val (bx, by) = positions.getValue(edge.toId)
                val dx = bx - ax
                val dy = by - ay
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.02f)
                val pull = ATTRACTION * dist
                val va = velocities.getValue(edge.fromId)
                val vb = velocities.getValue(edge.toId)
                velocities[edge.fromId] = (va.first + dx * pull) to (va.second + dy * pull)
                velocities[edge.toId] = (vb.first - dx * pull) to (vb.second - dy * pull)
            }
            for (id in selectedIds) {
                val (x, y) = positions.getValue(id)
                var (vx, vy) = velocities.getValue(id)
                // Gentle pull back to the center so nothing drifts off-canvas.
                vx += (0.5f - x) * CENTERING
                vy += (0.5f - y) * CENTERING
                var nx = x + vx * speed
                var ny = y + vy * speed
                nx = nx.coerceIn(0.03f, 0.97f)
                ny = ny.coerceIn(0.03f, 0.97f)
                positions[id] = nx to ny
            }
            speed *= COOLING
        }

        return Result(positions, selected.size, nodes.size)
    }

    /** Hubs first, then best-connected slips, then the rest in shelf order. */
    private fun selectNodes(
        nodes: List<LumenGraphNode>,
        maxNodes: Int,
    ): List<LumenGraphNode> {
        if (nodes.size <= maxNodes) return nodes
        val rank =
            compareByDescending<LumenGraphNode> { it.isHub }
                .thenByDescending { it.degree }
                .thenBy { it.address }
        return nodes.sortedWith(rank).take(maxNodes)
    }
}
