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
