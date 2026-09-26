package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * A "stray" note: captured, but never connected to anything else in the box.
 * Luhmann's slip box has no such thing by design — every slip earns its
 * place by relating to another, which is the whole point of a Zettelkasten
 * over a plain pile of notes. A stray note is real captured work sitting
 * outside that point entirely, easy to lose track of once the box grows past
 * a handful of cards. This is what the box's "N stray" indicator counts and
 * jumps to.
 */
object LumenStrayNotes {

    data class Queue(val count: Int, val oldest: LumenCardEntity?)

    /** How many cards, and which one to offer first — the oldest capture left unconnected. */
    fun queue(cards: List<LumenCardEntity>): Queue {
        val strays = cards.filter(::isStray)
        return Queue(count = strays.size, oldest = strays.minByOrNull { it.createdAt })
    }

    private fun isStray(card: LumenCardEntity): Boolean =
        LumenCapture.linksFromJson(card.linksJson).isEmpty()
}
