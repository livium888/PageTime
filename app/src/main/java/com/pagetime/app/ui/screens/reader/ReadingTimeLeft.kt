package com.pagetime.app.ui.screens.reader

/**
 * "1 hr 37 mins left in book", for the foot of the page.
 *
 * From the words still to read and an ordinary reading speed. It used to be
 * worked out from this sitting's progress divided by its seconds, which said
 * nothing for the first minute and then swung with every pause; the words left
 * in the book are known and do not swing.
 */
object ReadingTimeLeft {

    /** Silent reading of ordinary prose; Brysbaert's 2019 meta-analysis puts it near 240. */
    const val WORDS_PER_MINUTE = 240

    fun minutes(totalWords: Int?, progress: Float): Int? {
        val words = totalWords?.takeIf { it > 0 } ?: return null
        val left = words * (1f - progress.coerceIn(0f, 1f))
        return kotlin.math.ceil(left / WORDS_PER_MINUTE).toInt()
    }

    fun label(totalWords: Int?, progress: Float): String? {
        val minutes = minutes(totalWords, progress) ?: return null
        if (progress >= 0.995f || minutes <= 0) return null
        val hours = minutes / 60
        val rest = minutes % 60
        val time = when {
            hours == 0 -> plural(minutes, "min")
            rest == 0 -> plural(hours, "hr")
            else -> "${plural(hours, "hr")} ${plural(rest, "min")}"
        }
        return "$time left in book"
    }

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
}

/**
 * "12 of 20 min · 8 left", for the head of the page beside the clock.
 *
 * The reader should never have to leave the book to find out whether the time
 * they are putting in is being counted. When it is not, this says so and why,
 * because a counter that silently stands still looks exactly like a broken one.
 */
object ReadingToday {

    fun label(readSeconds: Long, targetSeconds: Long, lockOn: Boolean, notCounting: String? = null): String {
        val read = (readSeconds.coerceAtLeast(0L) / 60).toInt()
        val target = (targetSeconds / 60).toInt()
        val base = when {
            !lockOn || target <= 0 -> "$read min read today"
            read >= target -> "$read of $target min · done"
            else -> "$read of $target min · ${target - read} left"
        }
        return if (notCounting != null) "$base · $notCounting" else base
    }

    /** Why the clock has stopped, in the reader's terms; null while it is running. */
    fun notCounting(idle: Boolean, tooFast: Boolean): String? = when {
        tooFast -> "paused: pages turning too fast"
        idle -> "paused: turn a page to go on"
        else -> null
    }
}
