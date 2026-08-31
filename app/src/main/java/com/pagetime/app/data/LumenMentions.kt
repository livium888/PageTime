package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * Unlinked-mention detection — the Zettelkasten's "did you mean to link
 * these?" prompt. Luhmann's slips were connected by reading: when a thought
 * echoed an earlier one he filed a reference. This finds the echoes the box
 * already contains but that were never written down: whenever one slip's text
 * quotes another slip's title word-for-word (case-insensitively, whole word),
 * the pair is a candidate link.
 *
 * Rules, all enforced exactly:
 *  - the mention must match the whole title, not a fragment ("Hexagons" must
 *    not match "Hexagonality" and vice versa);
 *  - titles too short to be distinctive (under 5 characters) never match;
 *  - a title that is itself an address ("21a1", "21/2a7") is skipped — those
 *    are real references, already handled by [LumenReferences];
 *  - pairs that are already linked (either direction) are never suggested.
 */
object LumenMentions {
    data class Mention(
        val source: LumenCardEntity,
        val target: LumenCardEntity,
        /** How many times the target's title appears in the source's text. */
        val count: Int,
    )

    private val ADDRESS_SHAPED = Regex("^\\d+[a-zA-Z].*|.*[/,].*")

    /** The text Luhmann-style matching scans: idea, body, quote, and evolution. */
    private fun searchableText(card: LumenCardEntity): String {
        val snippets =
            LumenCapture.snippetsFromJson(card.snippetsJson)
                .joinToString(" ") { it.text }
        return listOf(card.front, card.back, card.quote, snippets).joinToString(" ")
    }

    /** All mention pairs in the box, ranked by strength. */
    fun suggest(
        cards: List<LumenCardEntity>,
        limit: Int = 20,
    ): List<Mention> {
        val linked = HashMap<String, Set<String>>()
        for (card in cards) {
            linked[card.id] = LumenCapture.linksFromJson(card.linksJson).toSet()
        }
        val results = mutableListOf<Mention>()
        for (source in cards) {
            val text = searchableText(source)
            if (text.isBlank()) continue
            for (target in cards) {
                if (target.id == source.id) continue
                val title = target.front.trim()
                if (!isUsableTitle(title)) continue
                if (target.id in (linked[source.id] ?: emptySet())) continue
                if (source.id in (linked[target.id] ?: emptySet())) continue
                val count = countMentions(text, title)
                if (count > 0) {
                    results += Mention(source, target, count)
                }
            }
        }
        // Stronger echoes first; ties broken by the newest source slip.
        return results
            .sortedWith(
                compareByDescending<Mention> { it.count }
                    .thenByDescending { it.source.updatedAt }
                    .thenBy { it.source.indexNumber },
            )
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    /** The unlinked mentions that point AT [card]'s title, from any other slip. */
    fun pointingAt(
        card: LumenCardEntity,
        cards: List<LumenCardEntity>,
        limit: Int = 5,
    ): List<Mention> =
        suggest(cards, limit = 100)
            .filter { it.target.id == card.id }
            .take(limit.coerceAtLeast(1))

    /** The unlinked mentions that [card] itself makes of other slips' titles. */
    fun madeBy(
        card: LumenCardEntity,
        cards: List<LumenCardEntity>,
        limit: Int = 5,
    ): List<Mention> =
        suggest(cards, limit = 100)
            .filter { it.source.id == card.id }
            .take(limit.coerceAtLeast(1))

    private fun isUsableTitle(title: String): Boolean {
        if (title.length < 5) return false
        if (ADDRESS_SHAPED.matches(title)) return false
        return true
    }

    /** Whole-title, case-insensitive occurrences with word boundaries. */
    fun countMentions(
        text: String,
        title: String,
    ): Int {
        val escaped = Regex.escape(title)
        val regex = Regex("(?i)(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])")
        return regex.findAll(text).count()
    }
}
