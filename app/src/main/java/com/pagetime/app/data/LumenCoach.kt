package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity

/**
 * One lesson in the method coach. Luhmann's actual practice, one idea per
 * lesson, each with a concrete practice step the user can do right now.
 */
data class LumenLesson(
    val id: String,
    val title: String,
    /** What Luhmann actually did — the historical fact, briefly. */
    val body: String,
    /** A concrete thing to do in this app right now. */
    val practice: String
)

/**
 * The method coach. This is NOT spaced repetition — Luhmann never drilled his
 * box. He worked it: he read with a pen, wrote slips in his own words, gave
 * each a fixed address, branched when a thought grew, and cross-referenced
 * across topics. Every lesson here is grounded in that historical practice
 * (Johannes F.K. Schmidt, "Niklas Luhmann's Card Index: The Thinking Tool
 * of a Social Scientist" and Luhmann's own "Kommunikation mit Zettelkästen").
 */
object LumenCoach {

    val lessons: List<LumenLesson> = listOf(
        LumenLesson(
            id = "own_words",
            title = "Write in your own words",
            body = "Luhmann's first rule: a slip is only useful if you rewrite the idea in your own words, in full sentences. Copying quotes feels productive but teaches nothing — the rewriting is where understanding happens. Keep one slip to one thought; if it needs a second thought, it needs a second slip.",
            practice = "Open your newest card (Edit) and check: is it in your words, or the book's? If it's a quote, rewrite the note above it."
        ),
        LumenLesson(
            id = "addresses",
            title = "Every slip has a fixed address",
            body = "Luhmann never renumbered. A slip's address (21, 21a, 21a1) is assigned when it's filed and stays for life — his cross-references point at addresses that never move. New notes continue the line (after 21 comes 22); a note that grows out of another branches with a letter (21 → 21a).",
            practice = "Tap any card in your box and look at its address badge. That identity is permanent — links you make will always resolve."
        ),
        LumenLesson(
            id = "file_behind",
            title = "File behind the thought it continues",
            body = "When Luhmann filed a new slip, he didn't ask \"which topic?\" — he asked \"which note does this continue?\" The new slip went directly behind its predecessor, branching with a letter if the predecessor already had followers. The box's physical order became a train of thought you can walk again years later.",
            practice = "Next time you capture, don't just append: open the card it continues and use \"File behind\" — the new card gets the next address in that branch."
        ),
        LumenLesson(
            id = "boxes",
            title = "A box is a line of work, not a topic folder",
            body = "Luhmann's leading number (21, 21a, 21a1) was a permanent branch of one continuous system — a train of thought — not a \"topic folder\" to sort ideas into. He never asked which category a slip belonged to; he asked which note it continued. His one real division was practical: bibliographic slips (the sources) lived apart from his main note slips. A new line of work is a new box; a new idea within that line is filed behind the slip it continues.",
            practice = "Box 1 is your main line. Start Box 2 only for a genuinely separate line of work (another project or discipline) — and keep linking across boxes: links, not addresses, connect different lines."
        ),
        LumenLesson(
            id = "links",
            title = "Links are the real index",
            body = "The address grid alone would trap ideas in one topic. So Luhmann added a second device: cross-references. On a slip about \"power\" he wrote \"→ 9/13\" wherever another box region mattered. His assistant Unkelbach maintained an index of these. Links are how a thought about law connects to a thought about biology — the box becomes a web, not a shelf.",
            practice = "Open two cards that are about the same thing from different angles and Link them. Do this once per reading session and the web grows by itself."
        ),
        LumenLesson(
            id = "hubs",
            title = "Let hub notes emerge",
            body = "When a cluster grew, Luhmann didn't create a category — he wrote a hub slip: a note whose only job is to list the addresses of the notes around one theme, updated as the cluster grows. The structure is discovered from the notes, never imposed before them.",
            practice = "Spot a theme that now has 3+ cards? File one new card as its hub: one line per related card, using their addresses. Then Link the hub to each."
        ),
        LumenLesson(
            id = "evolution",
            title = "Re-encounter, don't repeat",
            body = "Luhmann revisited old slips constantly — and when an old note met a new idea, he didn't rewrite it. He added a new slip behind it, or appended context. The box records the trail of your re-encounters; contradictions stay visible, and that friction is where original thoughts come from.",
            practice = "Reopen an old card that relates to what you just read and use \"+ Context\" to append today's thought — dated, additive, no rewriting."
        ),
        LumenLesson(
            id = "ritual",
            title = "The box is a communication partner",
            body = "Luhmann: the slip box is \"a communication partner\" with whom you can think. His routine was boring and daily: read with pen in hand, capture a few slips, file them, add a link or two. No heroics — the value compounds from small, regular deposits. He produced ~70 books from roughly 90,000 slips built exactly this way.",
            practice = "Make it a ritual: every reading session ends with one capture, one link. That's the whole method."
        )
    )

    /** Short contextual tips, cycled by usage. */
    val tips: List<String> = listOf(
        "One slip = one thought. If you need \"and\" in the title, split it.",
        "Never file by topic — file behind the note it continues.",
        "A new box is a new line of work, not a new folder. Ask \"what does this continue?\" before asking \"where does it live?\"",
        "A link you don't add today is a connection you'll never find later.",
        "Quotes are raw material. Your own words are the note.",
        "When a line of notes gets long, that's a chapter of your future book.",
        "Contradictions between slips are features — they mean you're thinking.",
        "The box rewards boring regularity, not heroic sessions."
    )

    /**
     * The next concrete move, derived from the box's actual state. This is the
     * coach speaking: not a quiz, a nudge toward the method's next habit.
     */
    fun nextStep(cards: List<LumenCardEntity>): String? {
        if (cards.isEmpty()) {
            return "Start by capturing one idea while reading: Options → New Lumen card. One slip, your own words."
        }
        val totalLinks = cards.sumOf { LumenCapture.linksFromJson(it.linksJson).size }
        if (totalLinks == 0) {
            val first = cards.first()
            return "You have ${cards.size} note${if (cards.size == 1) "" else "s"} but no links yet. Open \u201C${first.front.take(40)}\u201D and Link it to one other note."
        }
        val withSnippets = cards.count { LumenCapture.snippetsFromJson(it.snippetsJson).isNotEmpty() }
        if (withSnippets == 0 && cards.size >= 3) {
            return "Re-encounter time: reopen a card related to what you just read and append today's thought with \"+ Context\"."
        }
        val linkedCount = cards.count { LumenCapture.linksFromJson(it.linksJson).isNotEmpty() }
        if (cards.size >= 4 && linkedCount < cards.size / 2) {
            return "Half your notes float unconnected. Pick one orphan and link it — the web grows one thread at a time."
        }
        val hasHub = cards.any {
            it.back.trim().split('\n').size >= 3 &&
                LumenCapture.linksFromJson(it.linksJson).size >= 2
        }
        if (!hasHub && cards.size >= 6) {
            return "A theme is forming. File one hub card: a list of the addresses in that cluster, linked to each."
        }
        return "Steady state: capture while reading, file behind the thought it continues, add one link. The box does the rest."
    }
}
