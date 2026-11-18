package com.pagetime.app.data.youtube

import com.pagetime.app.data.BookGenre
import com.pagetime.app.data.recommend.ReaderPicks

/**
 * Which videos are worth reading, as opposed to watching.
 *
 * A transcript reads well when the video is mostly someone talking and making
 * an argument: a lecture, a talk, an interview, a conversation. It reads badly
 * when the video is music, a demonstration, a reaction, a compilation or a
 * trailer, and a transcript of a four-hour podcast is longer than the app's
 * tidy step can take. None of that is what YouTube ranks by, and what it does
 * rank by (views, engagement) is not a sign of quality: reviews of health and
 * education videos mostly find no relationship between popularity and
 * accuracy. So this does not use views to order anything.
 *
 * It uses only what a search result carries (title, channel, length,
 * description) and what the reader has already done: the channels they read
 * to the end. It cannot know whether a video is true or well argued, and
 * does not claim to.
 *
 * Pure, so every rule can be tested without a network.
 */
object ReadableVideos {

    data class Candidate(
        val videoId: String,
        val title: String,
        val channel: String,
        /** As YouTube shows it: "47:12" or "1:02:03"; blank for a live stream. */
        val duration: String,
        val description: String,
        /** As YouTube shows it: "1.2M views"; blank when unknown. */
        val views: String = "",
    )

    enum class Kind(val label: String?) {
        LECTURE("Lecture"),
        TALK("Talk"),
        INTERVIEW("Interview"),
        DOCUMENTARY("Documentary"),
        OTHER(null),
        /** Music, reactions, compilations, trailers: a transcript of these is not worth reading. */
        UNREADABLE(null),
    }

    enum class Fit { FITS, TOO_SHORT, TOO_LONG, UNKNOWN }

    data class Pick(
        val candidate: Candidate,
        val kind: Kind,
        val fit: Fit,
        val words: Int?,
        val readMinutes: Int?,
        /** One line saying why this is here. */
        val why: String,
        val score: Double,
    )

    /** Spoken English runs at about this many words a minute. */
    const val SPOKEN_WORDS_PER_MINUTE = 150

    /** Silent reading, per Brysbaert's 2019 meta-analysis. */
    const val READING_WORDS_PER_MINUTE = 240

    /** Shorter than this has too little to read. */
    const val MIN_SECONDS = 8 * 60

    /** The longest the tidy step can take: 12,000 words at spoken pace. */
    const val MAX_SECONDS = 12_000 * 60 / SPOKEN_WORDS_PER_MINUTE

    /** Below this many views a result is probably empty, not obscure. Not used to order anything. */
    private const val VIEW_FLOOR = 1_000L

    // region Reading what a result says

    /** "1:02:03" or "47:12" in seconds; null for anything else, including a live stream. */
    fun seconds(duration: String): Int? {
        val parts = duration.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toInt() }
        return if (numbers.size == 3) numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        else numbers[0] * 60 + numbers[1]
    }

    /** "1.2M views" as a number; null when it says nothing usable. */
    fun views(text: String): Long? {
        val match = Regex("([\\d.,]+)\\s*([KkMmBb]?)").find(text) ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val scale = when (match.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (number * scale).toLong()
    }

    fun words(seconds: Int): Int = seconds / 60 * SPOKEN_WORDS_PER_MINUTE

    fun readMinutes(words: Int): Int = ((words + READING_WORDS_PER_MINUTE - 1) / READING_WORDS_PER_MINUTE).coerceAtLeast(1)

    fun fit(seconds: Int?): Fit = when {
        seconds == null -> Fit.UNKNOWN
        seconds < MIN_SECONDS -> Fit.TOO_SHORT
        seconds > MAX_SECONDS -> Fit.TOO_LONG
        else -> Fit.FITS
    }

    private val UNREADABLE = listOf(
        "official video", "music video", "lyric video", "lyrics", "full album", "soundtrack", "remix",
        "reaction", "reacts to", "reacting to", "compilation", "trailer", "#shorts", "shorts",
        "top 10", "top 5", "top 20", "unboxing", "gameplay", "walkthrough", "asmr", "karaoke",
        "live stream", "livestream", "playlist", "hour of", "hours of", "1 hour", "mix)",
    )

    private val CLICKBAIT = listOf(
        "you won't believe", "you wont believe", "shocking", "insane", "mind blowing", "mind-blowing",
        "gone wrong", "must watch", "must see", "life changing", "life-changing", "destroyed", "exposed",
        "!!", "secret they", "what happened next",
    )

    private val INTERVIEW = listOf(
        "interview", "in conversation", "conversation with", "podcast", "episode", "q&a", "fireside",
        "discusses", "in discussion", "debate",
    )
    private val LECTURE = listOf(
        "lecture", "seminar", "colloquium", "masterclass", "symposium", "course", "lesson",
    )
    private val TALK = listOf("talk", "keynote", "speech", "ted ", "tedx", "address")
    private val DOCUMENTARY = listOf("documentary", "video essay", "essay", "explained")

    fun kind(candidate: Candidate): Kind {
        val text = (candidate.title + " " + candidate.channel + " " + candidate.description.take(200)).lowercase()
        val title = candidate.title.lowercase()
        if (UNREADABLE.any { it in title }) return Kind.UNREADABLE
        return when {
            LECTURE.any { it in text } -> Kind.LECTURE
            INTERVIEW.any { it in text } -> Kind.INTERVIEW
            TALK.any { it in text } -> Kind.TALK
            DOCUMENTARY.any { it in text } -> Kind.DOCUMENTARY
            else -> Kind.OTHER
        }
    }

    fun clickbait(title: String): Boolean {
        val lower = title.lowercase()
        if (CLICKBAIT.any { it in lower }) return true
        // Shouting: most of the letters in capitals.
        val letters = title.filter(Char::isLetter)
        return letters.length >= 12 && letters.count(Char::isUpperCase) > letters.length * 0.6
    }

    // endregion

    // region Ranking

    /** How the reader's length reads on a card: "about 4,500 words · 19 min read". */
    fun lengthLabel(pick: Pick): String? {
        val words = pick.words ?: return null
        val rounded = if (words >= 1_000) (words / 100) * 100 else (words / 10) * 10
        return "about " + "%,d".format(java.util.Locale.US, rounded) + " words · ${pick.readMinutes} min read"
    }

    /** The single line under a result saying why it is offered, or null when there is nothing to say. */
    private fun whyLine(kind: Kind, knownChannel: Boolean): String {
        val parts = listOfNotNull(
            kind.label,
            "a channel you've read before".takeIf { knownChannel },
        )
        return parts.joinToString(" · ")
    }

    private fun grade(
        candidate: Candidate,
        position: Int,
        knownChannels: Set<String>,
    ): Pick {
        val seconds = seconds(candidate.duration)
        val fit = fit(seconds)
        val kind = kind(candidate)
        val words = seconds?.let(::words)
        val known = candidate.channel.trim().lowercase() in knownChannels
        var score = 10.0 - position * 0.3
        score += when (kind) {
            Kind.LECTURE, Kind.TALK, Kind.INTERVIEW -> 3.0
            Kind.DOCUMENTARY -> 1.0
            Kind.OTHER -> 0.0
            Kind.UNREADABLE -> -100.0
        }
        if (clickbait(candidate.title)) score -= 3.0
        if (known) score += 2.0
        if (fit != Fit.FITS) score -= 50.0
        return Pick(
            candidate = candidate,
            kind = kind,
            fit = fit,
            words = words,
            readMinutes = words?.let(::readMinutes),
            why = whyLine(kind, known),
            score = score,
        )
    }

    /**
     * What a shelf shows: only videos that fit and read, best first, one per
     * channel, near-duplicates removed, nothing already in the library.
     */
    fun shelf(
        results: List<Candidate>,
        knownChannels: Set<String> = emptySet(),
        ownedTitles: Collection<String> = emptyList(),
        limit: Int = 6,
    ): List<Pick> {
        val owned = ownedTitles.map(VideoOwnership::titleKey).filter { it.isNotEmpty() }.toSet()
        val known = knownChannels.map { it.trim().lowercase() }.toSet()
        return results
            .asSequence()
            .distinctBy { it.videoId }
            .mapIndexed { index, candidate -> grade(candidate, index, known) }
            .filter { it.fit == Fit.FITS && it.kind != Kind.UNREADABLE }
            .filter { (views(it.candidate.views) ?: Long.MAX_VALUE) >= VIEW_FLOOR }
            .filter { VideoOwnership.titleKey(it.candidate.title) !in owned }
            .sortedByDescending { it.score }
            .distinctBy { VideoOwnership.titleKey(it.candidate.title) }
            .distinctBy { it.candidate.channel.trim().lowercase() }
            .take(limit)
            .toList()
    }

    /**
     * A search the reader typed themselves: nothing is hidden, since they asked
     * for it, but what reads well comes first and everything is labelled.
     */
    fun search(
        results: List<Candidate>,
        knownChannels: Set<String> = emptySet(),
    ): List<Pick> {
        val known = knownChannels.map { it.trim().lowercase() }.toSet()
        val graded = results.distinctBy { it.videoId }.mapIndexed { index, c -> grade(c, index, known) }
        val readable = graded.filter { it.fit == Fit.FITS && it.kind != Kind.UNREADABLE }
        return readable + graded.filterNot { it in readable }
    }

    /** A note for a result that is shown but does not suit reading, or null if it does. */
    fun caution(pick: Pick): String? = when {
        pick.kind == Kind.UNREADABLE -> "Mostly not speech, so the transcript won't read well"
        pick.fit == Fit.TOO_LONG -> "Too long to tidy with your AI in one go"
        pick.fit == Fit.TOO_SHORT -> "Very short: little to read"
        pick.fit == Fit.UNKNOWN -> "No length shown, so it may be a live stream"
        else -> null
    }

    // endregion

    // region What to search for

    /** A shelf to fill: a title and the searches that fill it. */
    data class Plan(val title: String, val queries: List<String>)

    private fun queriesFor(genre: BookGenre): Pair<String, List<String>>? = when (genre) {
        BookGenre.HISTORY -> "History" to listOf("history lecture", "historian interview", "history talk")
        BookGenre.SCIENCE -> "Science" to listOf("science lecture", "scientist interview", "science talk explained")
        BookGenre.PHILOSOPHY -> "Philosophy" to listOf("philosophy lecture", "philosopher interview", "philosophy talk")
        BookGenre.SELF_HELP -> "How we think and live" to listOf("psychologist lecture", "psychology research talk", "behavioural science interview")
        BookGenre.MEMOIR_BIOGRAPHY -> "Lives" to listOf("biographer interview", "memoir author talk", "biography lecture")
        BookGenre.NONFICTION -> "Ideas" to listOf("author interview nonfiction book", "public lecture", "book talk")
        BookGenre.FICTION, BookGenre.FANTASY, BookGenre.SCIENCE_FICTION,
        BookGenre.MYSTERY_THRILLER, BookGenre.ROMANCE ->
            "Writers on writing" to listOf("novelist interview", "author talk on writing", "writer lecture")
        BookGenre.POETRY -> "Poetry" to listOf("poet interview", "poetry lecture", "poet talk")
        BookGenre.OTHER -> null
    }

    private val GENERAL = Plan(
        "Talks and lectures",
        listOf("university lecture full", "long form interview expert", "public lecture series"),
    )

    /**
     * The shelves to fill: one per genre the reader has most of (at most
     * three), then a general one. The searches are plain descriptions of a
     * format and a subject, not "must watch"-style phrases, which pull in
     * whatever is already being pushed hardest.
     */
    fun plans(mostRead: List<BookGenre>): List<Plan> {
        val seen = mutableSetOf<String>()
        val genreShelves = mostRead
            .mapNotNull(::queriesFor)
            .filter { seen.add(it.first) }
            .take(3)
            .map { Plan(it.first, it.second) }
        return genreShelves + GENERAL
    }

    // endregion
}
