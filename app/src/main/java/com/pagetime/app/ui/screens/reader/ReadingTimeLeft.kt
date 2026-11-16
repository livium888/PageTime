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

    /** A chapter end further off than this is not "nearly there" and is not mentioned. */
    const val NEARLY_THERE_MINUTES = 15

    /**
     * @param showCountdown Whether what is left to the target is shown. Off, the
     *   line says only what has been read: a countdown keeps the eye on the exit.
     * @param chapterMinutesLeft Reading time to the end of the chapter, if known.
     *   Once the target is met this takes the place of "done". The target is an
     *   arbitrary minute to stop at and the end of a chapter is not, so the line
     *   points at the chapter end when it is close.
     */
    fun label(
        readSeconds: Long,
        targetSeconds: Long,
        lockOn: Boolean,
        notCounting: String? = null,
        showCountdown: Boolean = true,
        chapterMinutesLeft: Int? = null,
    ): String {
        val read = (readSeconds.coerceAtLeast(0L) / 60).toInt()
        val target = (targetSeconds / 60).toInt()
        val met = !lockOn || target <= 0 || read >= target
        val base = when {
            !met && showCountdown -> "$read of $target min · ${target - read} left"
            met && chapterMinutesLeft != null && chapterMinutesLeft in 1..NEARLY_THERE_MINUTES ->
                "$read min read · $chapterMinutesLeft min to the end of the chapter"
            else -> "$read min read today"
        }
        return if (notCounting != null) "$base · $notCounting" else base
    }

    /** Minutes to read [words] at an ordinary pace, rounded up; null for none. */
    fun minutesFor(words: Int): Int? =
        if (words <= 0) null else kotlin.math.ceil(words / ReadingTimeLeft.WORDS_PER_MINUTE.toDouble()).toInt()

    /** Why the clock has stopped, in the reader's terms; null while it is running. */
    fun notCounting(idle: Boolean, tooFast: Boolean): String? = when {
        tooFast -> "paused: pages turning too fast"
        idle -> "paused: turn a page to go on"
        else -> null
    }
}
