package com.pagetime.app.data.learning

import org.json.JSONException
import org.json.JSONObject

/**
 * Where the reader's next batch of cards starts, and what was last copied.
 *
 * THE START PERSISTS UNTIL CARDS ARE MADE FROM IT
 *
 * It is set the first time the book is opened with no marker, and only moves
 * when a batch is imported (to the end of what was copied). Opening the book
 * again does nothing. Resetting on every open would be simpler and would
 * silently drop yesterday's reading whenever no cards were made from it;
 * persisting never loses text, and the sheet shows how long the span has
 * grown so the reader can shorten it.
 *
 * THE PENDING END IS STORED BECAUSE THE READER LEAVES THE APP
 *
 * Copying the prompt sends the reader to another app, and Android may kill
 * this one while they are away. When the reply is pasted, the span's end is
 * where it was at COPY time, not wherever the book was reopened — otherwise
 * importing would advance the marker past text the model was never shown.
 */
data class SpanMarker(
    val start: SpanPoint,
    val setAt: Long,
    /** The end of the span last copied, or null when nothing is waiting for a reply. */
    val pendingEnd: SpanPoint? = null,
    /** The paragraph nudge applied to that end when it was copied. */
    val pendingAdjust: Int = 0,
) {
    fun withPending(end: SpanPoint, adjust: Int): SpanMarker =
        copy(pendingEnd = end, pendingAdjust = adjust)

    /**
     * After an import: the next span starts where this one ended.
     *
     * Pass [ReadingSpan.Slice.next], the exact point past the last paragraph
     * taken, and not the reader's position: the position snaps back to the
     * start of its paragraph and would take that paragraph a second time.
     */
    fun advancedTo(next: SpanPoint, now: Long): SpanMarker =
        SpanMarker(start = next, setAt = now)

    fun encode(): String {
        val obj = JSONObject()
            .put("start", start.toJson())
            .put("setAt", setAt)
            .put("adjust", pendingAdjust)
        pendingEnd?.let { obj.put("pendingEnd", it.toJson()) }
        return obj.toString()
    }

    companion object {
        /** Null for a missing or unreadable value: no marker, never a crash. */
        fun decode(raw: String?): SpanMarker? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                SpanMarker(
                    start = SpanPoint.fromJson(obj.getJSONObject("start")),
                    setAt = obj.optLong("setAt", 0L),
                    pendingEnd = obj.optJSONObject("pendingEnd")?.let(SpanPoint::fromJson),
                    pendingAdjust = obj.optInt("adjust", 0),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}
