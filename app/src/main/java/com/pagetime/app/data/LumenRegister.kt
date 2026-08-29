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

/**
 * The literature box — Luhmann's second Zettelkasten. His main notes did not
 * float free: each cited a bibliographic slip in the literature box, one per
 * source, and the literature slip recorded where in that source the note came
 * from. Here the role of the literature box is played by the library itself:
 * every captured card already carries a [LumenCardEntity.bookId], so sources
 * can be grouped, counted, and navigated with no schema change and no AI.
 */
object LumenSources {

    /** One bibliographic "slip": a source and the notes that cite it. */
    data class Source(
        val bookId: String,
        val title: String,
        val author: String,
        val cards: List<LumenCardEntity>
    )

    /**
     * Groups captured cards by their source book, in shelf order within each
     * source. Desk cards (bookId = "") are not literature — they have no
     * source — so they are excluded; they live in the box, not the bibliography.
     */
    fun group(cards: List<LumenCardEntity>, books: List<BookMeta>): List<Source> {
        val byBook = cards
            .filter { it.bookId.isNotBlank() }
            .groupBy { it.bookId }
        return byBook
            .map { (bookId, cardList) ->
                val meta = books.firstOrNull { it.id == bookId }
                Source(
                    bookId = bookId,
                    title = meta?.title ?: "Unknown source",
                    author = meta?.author ?: "",
                    cards = cardList.sortedWith(
                        compareBy<LumenCardEntity> { it.box }.thenBy(LumenAddress.COMPARATOR) { it.indexNumber }
                    )
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    /** Minimal book metadata the grouping needs — decoupled from BookEntity. */
    data class BookMeta(val id: String, val title: String, val author: String)

    /**
     * Plain-text rendering of one source's slip list: the bibliography line
     * followed by every note citing it, in shelf order — the reading-notes
     * digest Luhmann's literature slips enabled.
     */
    fun render(source: Source): String {
        val sb = StringBuilder()
        sb.append(source.title)
        if (source.author.isNotBlank()) sb.append(" — ").append(source.author)
        sb.append('\n')
        for (card in source.cards) {
            sb.append(card.indexNumber.ifBlank { "?" }).append("  ")
            sb.append(card.front).append('\n')
            if (card.quote.isNotBlank()) {
                sb.append("    \u201C")
                sb.append(card.quote.replace("\n", " ").take(160))
                if (card.quote.length > 160) sb.append("…")
                sb.append("\u201D\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
