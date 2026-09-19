package com.pagetime.app.data

/**
 * The one sentence [LibrarianSuggester] is allowed to say without asking an
 * AI anything — built entirely from real fields already on the book
 * ([com.pagetime.app.data.local.BookEntity.addedAt],
 * [com.pagetime.app.data.local.BookEntity.scrollProgress]). This is what gets
 * shown when no AI is configured, and it is also the only "fact" an AI is
 * ever handed to reword — see [LibrarianPrompts.rephrase]. Never a claim
 * about research, psychology, or what the reader "needs"; only what's true
 * of this one book in this one library.
 */
object LibrarianFacts {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun sentence(pick: LibrarianPicks.Pick, nowMillis: Long): String {
        val book = pick.book
        return when (pick.reason) {
            LibrarianPicks.Reason.NEVER_OPENED -> {
                val days = ((nowMillis - book.addedAt).coerceAtLeast(0)) / MILLIS_PER_DAY
                "You added \"${book.title}\" ${humanizeDaysAgo(days)} and haven't started it yet " +
                    "— it's still there whenever you're ready."
            }
            LibrarianPicks.Reason.ALMOST_DONE -> {
                val percent = (book.scrollProgress * 100).toInt()
                "You're $percent% through \"${book.title}\" — so close to finishing."
            }
        }
    }

    private fun humanizeDaysAgo(days: Long): String = when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 7L -> "$days days ago"
        days < 60L -> {
            val weeks = days / 7
            "$weeks week${if (weeks == 1L) "" else "s"} ago"
        }
        else -> {
            val months = days / 30
            "$months month${if (months == 1L) "" else "s"} ago"
        }
    }
}
