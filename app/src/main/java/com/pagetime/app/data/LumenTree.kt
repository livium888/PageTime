package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * The box as a tree — the digital equivalent of the official archive's
 * Inhaltsübersicht (content overview). Luhmann's box is a handful of trunk
 * lines (1, 2, 3…) that branch off thousands of times; this rebuilds that
 * structure from the addresses alone. A slip's parent is the existing slip
 * with the longest address that properly contains it (21a1 hangs under 21a,
 * under 21), and 210 is NOT a child of 21 — it is its own trunk line, exactly
 * as in the original box. Trunks and siblings stay in true shelf order.
 */
object LumenTree {
    /** One node of the box map: a slip, its direct branches, and its size. */
    data class Node(
        val card: LumenCardEntity,
        val children: List<Node>,
        /** Every slip filed under this node (its whole subtree), itself excluded. */
        val descendantCount: Int,
    )

    /**
     * Builds the map of a box. Cards without an address are omitted — they
     * have no place in the structure. Returns the trunk lines in shelf order.
     */
    fun build(cards: List<LumenCardEntity>): List<Node> {
        val byAddress =
            cards
                .filter { it.indexNumber.isNotBlank() }
                .associateBy { it.indexNumber.trim() }
        if (byAddress.isEmpty()) return emptyList()

        // A slip's parent is the longest proper ancestor address that exists.
        // Deleted middle slips are skipped, so the tree shows the structure
        // as it actually stands (same rule as LumenAddress.threadPath).
        fun parentOf(card: LumenCardEntity): LumenCardEntity? {
            val address = card.indexNumber.trim()
            var best: LumenCardEntity? = null
            for (other in byAddress.values) {
                val otherAddress = other.indexNumber.trim()
                if (otherAddress != address && LumenAddress.isDescendantOf(address, otherAddress)) {
                    if (best == null || otherAddress.length > best.indexNumber.trim().length) {
                        best = other
                    }
                }
            }
            return best
        }

        val childrenOf = mutableMapOf<String, MutableList<LumenCardEntity>>()
        val roots = mutableListOf<LumenCardEntity>()
        for (card in byAddress.values) {
            val parent = parentOf(card)
            if (parent == null) roots.add(card) else childrenOf.getOrPut(parent.id) { mutableListOf() }.add(card)
        }

        fun node(card: LumenCardEntity): Node {
            val children =
                childrenOf[card.id].orEmpty()
                    .sortedWith(compareBy(LumenAddress.COMPARATOR) { it.indexNumber })
                    .map { node(it) }
            return Node(card, children, children.sumOf { it.descendantCount + 1 })
        }

        return roots
            .sortedWith(compareBy(LumenAddress.COMPARATOR) { it.indexNumber })
            .map { node(it) }
    }
}
