package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * The role a slip plays in an emerging argument. Luhmann did not type his
 * cross-references, but when he wrote he arranged slips by what they did for
 * the text — claim, evidence, objection — and the outline fell out. These are
 * lightweight writing aids: they exist only inside a manuscript, never on the
 * cards themselves.
 */
enum class LumenRole(val label: String) {
    CLAIM("Claim"),
    EVIDENCE("Evidence"),
    EXAMPLE("Example"),
    OBJECTION("Objection"),
    QUESTION("Question"),
    APPLICATION("Application")
}

/** One slip brought into a manuscript, with an optional argumentative role. */
data class ManuscriptEntry(
    val card: LumenCardEntity,
    val role: LumenRole? = null
)

/**
 * The writing desk — Luhmann's third organ after the box and the register.
 * When he wanted to produce something new he asked the box a question, took
 * out the slips that answered it (whole lines, not single Zetteln), ordered
 * them into an argument, and wrote from that arrangement. Everything here is
 * deterministic and on-device: no AI, no network, no new storage.
 */
object LumenManuscript {

    /**
     * Asks the box a question and returns the slips that speak to it. A card
     * counts when one of the question's significant terms appears in its
     * front, back, quote, or keywords. Once a slip matches, its whole line is
     * pulled too (21a, 21a1, 21b…) — writing starts from threads, not islands.
     */
    fun gather(
        question: String,
        cards: List<LumenCardEntity>,
        limit: Int = 15
    ): List<LumenCardEntity> {
        val terms = question.trim().lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 }
            .toSet()
        if (terms.isEmpty() || cards.isEmpty()) return emptyList()

        val scored = cards.map { card ->
            val haystack = buildString {
                append(card.front).append(' ')
                append(card.back).append(' ')
                append(card.quote).append(' ')
                append(card.keywords)
            }.lowercase()
            card to terms.count { haystack.contains(it) }
        }
        val ordered = scored
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<LumenCardEntity, Int>> { it.second }
                    .thenBy(LumenAddress.COMPARATOR) { it.first.indexNumber }
            )
            .map { it.first }

        val picked = linkedSetOf<String>()
        val result = mutableListOf<LumenCardEntity>()
        for (card in ordered) {
            if (picked.add(card.id)) result.add(card)
            // Pull the whole line behind the match, like taking slips off the
            // shelf: 21a, 21a1, 21b — never just the single Zettel.
            cards
                .filter { it.id != card.id && LumenAddress.isDescendantOf(it.indexNumber, card.indexNumber) }
                .sortedWith(compareBy(LumenAddress.COMPARATOR) { it.indexNumber })
                .forEach { descendant ->
                    if (picked.add(descendant.id)) result.add(descendant)
                }
            if (result.size >= limit) break
        }
        return result.take(limit.coerceIn(1, 100))
    }

    /**
     * Renders the manuscript as plain text: the question, the arranged slips
     * with addresses and roles, and the user's own draft. Ready to copy into
     * any editor — the box hands you the skeleton of the text.
     */
    fun render(
        question: String,
        entries: List<ManuscriptEntry>,
        draft: String
    ): String {
        val sb = StringBuilder()
        if (question.isNotBlank()) {
            sb.append("Question: ").append(question.trim()).append("\n\n")
        }
        if (entries.isEmpty()) {
            sb.append("(no slips gathered yet)")
        } else {
            entries.forEachIndexed { index, entry ->
                val role = entry.role?.let { "[${it.label}] " } ?: ""
                sb.append(index + 1).append(". ").append(role)
                sb.append(entry.card.indexNumber.ifBlank { "?" })
                sb.append(" — ").append(entry.card.front)
                sb.append('\n')
                val body = entry.card.back.ifBlank { entry.card.quote }
                if (body.isNotBlank()) {
                    val flat = body.replace("\n", " ").trim()
                    sb.append("   ").append(flat.take(220))
                    if (flat.length > 220) sb.append("…")
                    sb.append('\n')
                }
            }
        }
        if (draft.isNotBlank()) {
            sb.append("\nDraft:\n\n").append(draft.trim())
        }
        return sb.toString().trimEnd()
    }
}
