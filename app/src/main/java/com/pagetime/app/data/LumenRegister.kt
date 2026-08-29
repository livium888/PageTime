package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * The Register — Luhmann's keyword index, the organ that made 90,000 slips
 * navigable. One line per keyword, each pointing at an *entry address* in the
 * box: look up "power", get "21/2", and walk the line from there. Built
 * entirely on-device from the cards' own keywords and fronts; no AI.
 */
object LumenRegister {

    /** One keyword entry: the term and the addresses it opens onto. */
    data class Entry(
        val keyword: String,
        val addresses: List<String>,
        val cardIds: List<String>
    )

    /**
     * Builds the register from the box. Keywords come from the card's stored
     * [LumenCardEntity.keywords] (already extracted at capture) plus the
     * significant words of its front line. Cards without an address are
     * skipped — they have nothing to point at yet.
     */
    fun build(cards: List<LumenCardEntity>): List<Entry> {
        data class Acc(val ids: MutableSet<String>, val addresses: MutableSet<String>)

        val byKeyword = LinkedHashMap<String, Acc>()
        for (card in cards) {
            val address = card.indexNumber.trim()
            if (address.isEmpty()) continue
            val terms = buildSet {
                addAll(card.keywords.split(' ').filter { it.length >= 4 })
                LumenCapture.extractKeywords(card.front, max = 4)
                    .split(' ')
                    .filter { it.length >= 4 }
                    .forEach { add(it) }
            }
            for (term in terms) {
                byKeyword.getOrPut(term) { Acc(mutableSetOf(), mutableSetOf()) }
                    .let { acc ->
                        acc.ids.add(card.id)
                        acc.addresses.add(address)
                    }
            }
        }
        return byKeyword
            .map { (keyword, acc) ->
                Entry(
                    keyword = keyword,
                    addresses = acc.addresses.sortedWith(LumenAddress.COMPARATOR),
                    cardIds = acc.ids.toList()
                )
            }
            .sortedBy { it.keyword }
    }
}

/**
 * Pulling a thread — the act of writing. Luhmann would gather a starting slip
 * and everything filed behind it (its whole line: 21, 21a, 21a1, 21b…) and lay
 * them on his desk; the manuscript outline fell out of the chain. This pulls
 * the same thread from the digital box, in shelf order, ready to read or copy.
 */
object LumenThread {

    /** One step of a pulled thread. */
    data class Step(
        val card: LumenCardEntity,
        val depth: Int
    )

    /**
     * Everything filed behind [root] in shelf order: the root itself, its
     * children (21a, 21b…), their children (21a1…), depth-first — the exact
     * order the slips sit in the box. Uses [LumenAddress.isDescendantOf], so
     * 210 is correctly NOT part of 21's thread.
     */
    fun pull(root: LumenCardEntity, all: List<LumenCardEntity>): List<Step> {
        val behind = all
            .filter { it.id != root.id && LumenAddress.isDescendantOf(it.indexNumber, root.indexNumber) }
            .sortedWith(compareBy(LumenAddress.COMPARATOR) { it.indexNumber })
        return listOf(Step(root, 0)) + behind.map { Step(it, it.indexNumber.length - root.indexNumber.length) }
    }

    /**
     * Plain-text rendering of the thread: an outline with indentation and
     * addresses, ready to paste into an editor. This is the "writing out"
     * moment — the box hands you a draft.
     */
    fun render(steps: List<Step>): String {
        val sb = StringBuilder()
        steps.forEach { step ->
            val indent = "  ".repeat(step.depth.coerceAtLeast(0))
            val address = step.card.indexNumber.ifBlank { "?" }
            sb.append(indent).append(address).append("  ")
            sb.append(step.card.front).append('\n')
            if (step.card.back.isNotBlank()) {
                sb.append(indent).append("    ")
                sb.append(step.card.back.replace("\n", " ")).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
