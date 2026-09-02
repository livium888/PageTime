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
            title = "A few trunk lines, not one topic per note",
            body = "The box starts with zero topics. The first note is 1, the next main line 2, then 3 — a handful of trunk lines, never one per subject. Everything else branches off them: a note that grows out of a line branches with a letter (1 → 1a), that branch's children get numbers (1a → 1a1), alternating forever (1a1 → 1a1a). Even with 3,000 notes you still run on a few trunks — 1 for broad human ideas, 2 for the natural sciences, 3 for tools. A slash (21/2a7) marks a slip filed in one spot that continues a different line — Luhmann's cross-reference. The address is assigned when a slip is filed and never changes, so his references always resolve.",
            practice = "Look at your box: 1, 2, 3… are the trunk lines; 2a, 2a1 sit under 2 as its branches. A new topic is never a new trunk — it's a branch off the note that inspired it."
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
            title = "Structure maps: hub notes handle the scale",
            body = "When a branch grew, Luhmann didn't create a category — he wrote a structure map: a single slip whose only job is to list the addresses where a cluster begins, updated as the cluster grows. The main index points to just 10–20 hubs, and each hub walks you into a whole web of ideas. Structure is discovered from the notes, never imposed before them.",
            practice = "Spot a theme that now has 3+ cards? File one new card as its hub: one line per starting point, using their addresses. Link the hub to each, then mark it as a hub note from its actions — it now leads the Register, your index."
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
        "1, 2, 3… are your trunk lines; 2a and 2a1 branch under 2. Same number, deeper suffix = same train of thought.",
        "A new topic is not a new trunk — it's a branch behind the note that inspired it.",
        "Your main index should point to 10–20 hub notes, not 3,000 topics.",
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
        val hubs = cards.filter { it.isHub }
        if (hubs.isEmpty() && cards.size >= 6) {
            return "A theme is forming. File one card as its hub: list the starting addresses of that cluster, link the hub to each, then mark it as a hub note."
        }
        if (hubs.isNotEmpty()) {
            return "You have ${hubs.size} hub note${if (hubs.size == 1) "" else "s"}. Open the Register and tap a hub to walk into its cluster."
        }
        return "Steady state: capture while reading, file behind the thought it continues, add one link. The box does the rest."
    }
}
