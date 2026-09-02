package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * In-text references — Luhmann's own cross-reference habit, made clickable.
 * He wrote his Verweisungen straight into a slip's text ("Einheit und
 * Führerschaft 21,7", "vgl. insb. 57,4e7b1e", "grundsätzlich 78,17") instead
 * of relying on any external index, and the archive hyperlinks those mentions.
 * This detects address-shaped tokens in a card's text that resolve to a real
 * card in the box, so the same references become tappable in Lumen.
 *
 * Tokens match whole and case-insensitively, so "21a1" never links to "21a"
 * just because it starts with it — a mention points at exactly the slip that
 * was written. Both notations are recognized: comma sub-numbers (1,1 / 13,4g1
 * as in Zettelkasten I) and slash cross-references (21/2a7 as in Zettelkasten II).
 */
object LumenReferences {
    /** One resolvable mention: the written address and its span in the text. */
    data class Mention(
        val address: String,
        val cardId: String,
        val start: Int,
        val end: Int,
    )

    private val TOKEN = Regex("[A-Za-z0-9]+(?:[/,][A-Za-z0-9]+)*")

    /**
     * Address-shaped tokens in [text] that match an existing card, in order of
     * appearance. A token only matches when the whole token is a known address.
     */
    fun find(
        text: String,
        all: List<LumenCardEntity>,
    ): List<Mention> {
        val byAddress =
            all
                .mapNotNull { card ->
                    card.indexNumber.trim().takeIf { it.isNotEmpty() }?.let { address ->
                        LumenAddress.normalize(address) to card
                    }
                }
                .toMap()
        if (byAddress.isEmpty()) return emptyList()
        return TOKEN.findAll(text).mapNotNull { match ->
            val card = byAddress[LumenAddress.normalize(match.value)]
            card?.let {
                Mention(it.indexNumber.trim(), it.id, match.range.first, match.range.last + 1)
            }
        }.toList()
    }
}
