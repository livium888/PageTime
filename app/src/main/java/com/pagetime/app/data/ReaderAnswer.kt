package com.pagetime.app.data

/** One labelled part of an answer. Absent parts are dropped before display. */
data class NotePart(val label: String, val text: String)

/**
 * An answer about something the reader pointed at, ready to show.
 *
 * Both selection actions — explaining a word and saying a sentence again
 * simply — end in the same sheet: a heading, the text being asked about, some
 * labelled parts, and a line saying where the answer came from. Mapping both
 * onto one shape keeps that one sheet rather than growing a second near-copy of
 * it per action.
 */
data class ReaderAnswer(
    val heading: String,
    val badge: String?,
    val quoted: String,
    val parts: List<NotePart>,
    val footnote: String,
    val source: LlmProviderKind?,
)

fun Gloss.asAnswer(): ReaderAnswer = ReaderAnswer(
    heading = term,
    badge = parts.kind,
    quoted = sentence,
    parts = listOfNotNull(
        parts.meaning?.let { NotePart("MEANS", it) },
        parts.here?.let { NotePart("HERE", it) },
        parts.example?.let { NotePart("FOR EXAMPLE", "“$it”") },
    ),
    footnote = "Written by the AI, not taken from a dictionary.",
    source = source,
)

fun PlainReading.asAnswer(): ReaderAnswer = ReaderAnswer(
    heading = "In plain English",
    badge = null,
    quoted = passage,
    parts = listOfNotNull(
        NotePart("SAYS", parts.plain),
        parts.words?.let { NotePart("HARD WORDS", it) },
    ),
    footnote = "The AI's rewording. The original is above it — check they agree.",
    source = source,
)
