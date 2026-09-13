package com.pagetime.app.data.review

/**
 * How large the text on a review card is drawn.
 *
 * Anki has had this control forever, and the reason is that the right size is
 * not a design decision — it is a property of the reader's eyes and of how
 * dense the cards they generated happen to be. A sitting whose questions are
 * three lines long wants a different size from one whose questions are three
 * sentences long, and a phone in one hand wants a different size from the same
 * phone propped up on a desk.
 *
 * The default is deliberately the MIDDLE of the range rather than the largest
 * thing that fits. The screen the reader is looking at is mostly card, so a
 * question drawn too big pushes its own answer off the bottom of the page and
 * turns reading it back into a scroll — which is what the small end is for.
 *
 * [scale] multiplies both size and line height, so the ratio between them —
 * the thing that actually decides whether a paragraph is comfortable — is
 * preserved at every step.
 */
enum class CardTextSize(val key: String, val label: String, val scale: Float) {
    SMALL("small", "Small", 0.85f),
    MEDIUM("medium", "Medium", 1.0f),
    LARGE("large", "Large", 1.2f);

    companion object {
        val DEFAULT = MEDIUM

        /**
         * The size stored under [key], or the default.
         *
         * An unknown key is not an error: a value written by a newer build and
         * then downgraded, or a corrupted preference, should leave the reader
         * with a readable card rather than a crash on open.
         */
        fun fromKey(key: String?): CardTextSize =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
