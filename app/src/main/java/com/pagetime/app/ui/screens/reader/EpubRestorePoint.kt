package com.pagetime.app.ui.screens.reader

import org.json.JSONObject

/**
 * Where to reopen an EPUB so that it opens on the page it was closed on.
 *
 * WHY THE EXACT POSITION IS THE WRONG ONE TO SAVE
 *
 * The reader reports a page as how far through its chapter the page BEGINS:
 * page 40 of 100 is 0.40 exactly. To reopen there, the engine multiplies that
 * by the chapter's width and rounds down to a whole page. 0.40 sits exactly on
 * the line between pages 39 and 40, so the smallest difference in the
 * arithmetic, or in the width after the chapter is laid out again, rounds it
 * down to page 39. Each time the book was reopened it could land a page
 * earlier than it was left, and the reader saved that as the new position.
 * Leave and come back a few times and it had gone back a few pages.
 *
 * So what is saved is a point half a page further in: 0.405. It is in the
 * middle of page 40, nowhere near a line, and rounds to page 40 whatever the
 * arithmetic does.
 *
 * Pure, so this can be tested without a reader.
 */
object EpubRestorePoint {

    /** Used until two pages of a chapter have been seen: small enough to stay in the page of any real chapter. */
    const val FALLBACK_NUDGE = 0.0002

    /**
     * The size of one page of a chapter, learned from two positions in it.
     *
     * The smallest gap ever seen between two different positions is one page;
     * a jump through the contents gives a larger gap and is ignored.
     */
    fun pageStep(known: Double?, previous: Double, current: Double): Double? {
        val gap = kotlin.math.abs(current - previous)
        if (gap <= 1e-9) return known
        return if (known == null) gap else minOf(known, gap)
    }

    /** [progression] moved half a page into its page, so that reopening cannot round down to the page before. */
    fun inside(progression: Double, pageStep: Double?): Double {
        val p = progression.coerceIn(0.0, 1.0)
        val nudge = if (pageStep != null && pageStep > 0.0 && pageStep <= 0.5) pageStep / 2 else FALLBACK_NUDGE
        return (p + nudge).coerceAtMost(0.99999)
    }

    /** The saved position [locatorJson] with its place in the chapter replaced; unchanged if it cannot be read. */
    fun withProgression(locatorJson: String, progression: Double): String = runCatching {
        val locator = JSONObject(locatorJson)
        val locations = locator.optJSONObject("locations") ?: return locatorJson
        locations.put("progression", progression)
        locator.toString()
    }.getOrDefault(locatorJson)
}
