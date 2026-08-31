package com.pagetime.app.data

/**
 * Newcomer onboarding for the slip box. To someone who has never used a
 * Zettelkasten, the card actions sound alike — Link, Connect, File behind —
 * and none of them explains what it does until it's already open. This maps
 * each action to a plain-language explanation (and a confirmation) so a new
 * reader is told what they're about to do before anything is changed.
 *
 * The text lives here, Android-free, so it's unit-testable and easy to edit.
 * The [LumenCoach] "Learn the method" section stays the deeper dive; this is
 * the two-line tutelary that appears in the moment of deciding.
 */
object LumenOnboarding {
    enum class Action(
        val title: String,
        val what: String,
        val confirmLabel: String,
    ) {
        LINK(
            title = "Link",
            what =
                "Creates a permanent two-way cross-reference: this card and the one you " +
                    "pick next will each list the other under \"Linked notes\". Links are the " +
                    "bonds of your box — when Luhmann connected two thoughts he filed a " +
                    "reference exactly like this. Nothing is changed until you choose the " +
                    "other card.",
            confirmLabel = "Yes — Link",
        ),
        CONNECT(
            title = "Connect",
            what =
                "Searches your whole box for cards that already share ideas with this one, " +
                    "ranked by how related they are. It's a suggestion engine — it doesn't link " +
                    "anything itself. You see a shortlist, and you decide which, if any, becomes " +
                    "a real link.",
            confirmLabel = "Yes — Connect",
        ),
        FILE_BEHIND(
            title = "File behind",
            what =
                "Filing tucks this card immediately behind another one on the shelf, growing " +
                    "that line: a card filed behind 21a becomes the next address on the 21a " +
                    "branch (21a1). It's how Luhmann filed a new thought next to the one that " +
                    "inspired it. Your card's address changes to sit right behind the card you " +
                    "pick.",
            confirmLabel = "Yes — File behind",
        ),
        PULL_THREAD(
            title = "Pull thread",
            what =
                "Reads the whole line this card belongs to — it and every slip filed behind " +
                    "it — as one unfolding outline you can copy out. Great when a chain of notes " +
                    "has grown long and you want to see the shape of the argument at a glance.",
            confirmLabel = "Yes — Pull thread",
        ),
    }
}
