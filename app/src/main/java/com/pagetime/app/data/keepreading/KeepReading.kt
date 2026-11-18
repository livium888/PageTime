package com.pagetime.app.data.keepreading

import org.json.JSONArray
import org.json.JSONObject

/**
 * What to read after finishing something: the prompt for the reader's own AI
 * app, the reading of its reply, and the list the answers are kept in.
 *
 * PageTime never calls a model, and an AI answering from memory invents titles
 * and links. So the prompt asks for plain lines that can be read without a
 * model, and every suggestion is opened as a search rather than a trusted link.
 */
object KeepReading {

    /** Which way to go from what was just read. */
    enum class Direction(val label: String, val ask: String) {
        DEEPER("Go deeper", "GO DEEPER: the same subject, harder or more specialised than what I read."),
        RELATED("Related", "SIDEWAYS: a different subject that sheds light on the same ideas."),
        OPPOSITE("The opposite view", "THE OPPOSITE VIEW: serious work that disagrees with or challenges it."),
        EASIER("Easier start", "EASIER: a gentler or more introductory way into the same subject."),
        WIDER("Wider field", "WIDER: the field around it, so I see where it sits."),
    }

    /** What kind of material a suggestion is. */
    enum class Kind(val label: String, val tag: String) {
        BOOK("Books", "BOOK"), VIDEO("Videos", "VIDEO"), ARTICLE("Articles", "ARTICLE"), PDF("PDFs and papers", "PDF");

        companion object {
            /** Reads the AI's word for a kind, however it wrote it. */
            fun parse(word: String): Kind? {
                val w = word.lowercase()
                return when {
                    "video" in w || "youtube" in w || "lecture" in w || "talk" in w -> VIDEO
                    "pdf" in w || "paper" in w -> PDF
                    "book" in w || "ebook" in w || "novel" in w -> BOOK
                    "article" in w || "essay" in w || "blog" in w || "post" in w || "web" in w -> ARTICLE
                    else -> null
                }
            }
        }
    }

    /** What the prompt says about the thing just finished. */
    data class Finished(
        val title: String,
        val author: String,
        /** "book", "PDF", "video transcript", "text"… */
        val what: String,
        val highlights: List<String> = emptyList(),
        val concepts: List<String> = emptyList(),
        /** Titles already in the library, so they are not suggested back. */
        val owned: List<String> = emptyList(),
    )

    /** One thing to read next. */
    data class Suggestion(
        val title: String,
        val creator: String,
        val kind: Kind,
        val why: String,
        /** Whatever the AI gave; shown but never opened blindly. */
        val link: String = "",
    )

    private const val MAX_HIGHLIGHTS = 8
    private const val MAX_HIGHLIGHT_CHARS = 160
    private const val MAX_CONCEPTS = 10
    private const val MAX_OWNED = 25

    fun buildPrompt(
        finished: Finished,
        direction: Direction,
        kinds: Set<Kind>,
        count: Int = 6,
    ): String = buildString {
        val by = finished.author.trim().takeIf { it.isNotEmpty() }?.let { " by $it" }.orEmpty()
        appendLine("I have just finished reading “${finished.title.trim()}”$by (${finished.what}).")
        val marks = finished.highlights.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_HIGHLIGHT_CHARS) }
            .filter { it.isNotEmpty() }.take(MAX_HIGHLIGHTS)
        if (marks.isNotEmpty()) {
            appendLine("What stayed with me (my highlights): " + marks.joinToString(", ") { "“$it”" })
        }
        val ideas = finished.concepts.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_CONCEPTS)
        if (ideas.isNotEmpty()) appendLine("Ideas I met: ${ideas.joinToString(", ")}.")
        appendLine("Direction: ${direction.ask}")
        val wanted = (kinds.ifEmpty { Kind.entries.toSet() }).joinToString(", ") { it.label.lowercase() }
        appendLine("Formats: $wanted.")
        val owned = finished.owned.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_OWNED)
        if (owned.isNotEmpty()) appendLine("Skip what I already have: ${owned.joinToString(", ")}.")
        appendLine(
            "Please search the web and suggest $count items. Only suggest things you are sure exist; " +
                "give a link only if you are certain of it."
        )
        append("Reply as a numbered list, one item per line, in exactly this form:\n")
        append("Title | creator | kind (book / video / article / PDF) | one sentence on why it fits | link")
    }

    /**
     * Reads the AI's reply: numbered or bulleted lines of `Title | creator | kind | why | link`.
     * Lines that do not look like one are skipped; no usable line at all is a
     * failure the reader is told how to fix.
     */
    fun parseReply(reply: String): Result<List<Suggestion>> {
        val found = reply.lines().mapNotNull { parseLine(it) }
            .distinctBy { it.title.lowercase() }
        return if (found.isEmpty()) {
            Result.failure(
                IllegalArgumentException(
                    "I could not find a list in that reply. Ask your AI to reply as lines of " +
                        "Title | creator | kind | why | link."
                )
            )
        } else Result.success(found)
    }

    private fun parseLine(raw: String): Suggestion? {
        val line = raw.trim()
            .replace(Regex("^\\s*(?:\\d+[.)]|[-*•])\\s*"), "")
            .replace("**", "").replace("__", "")
            .trim().trim('|').trim()
        if ('|' !in line) return null
        val parts = line.split('|').map { it.trim() }
        // A header or divider row from a markdown table is not a suggestion.
        if (parts.all { it.matches(Regex("[-: ]*")) }) return null
        if (parts.size < 3) return null
        val title = parts[0].trim('“', '”', '"').trim()
        if (title.isEmpty() || title.equals("title", ignoreCase = true)) return null
        val kind = Kind.parse(parts[2]) ?: Kind.ARTICLE
        return Suggestion(
            title = title,
            creator = parts[1],
            kind = kind,
            why = parts.getOrElse(3) { "" },
            link = parts.getOrElse(4) { "" }.takeIf { it.startsWith("http") }.orEmpty(),
        )
    }

    /**
     * Whether what is on the clipboard is worth trying as the AI's reply. Quiet
     * about everything else a clipboard holds, including the prompt itself.
     */
    fun looksLikeReply(clip: String, prompt: String, alreadyTried: String?): Boolean {
        val text = clip.trim()
        if (text.isEmpty() || text == alreadyTried?.trim()) return false
        if (text == prompt.trim()) return false
        val probe = prompt.trim().take(80)
        if (probe.length >= 40 && probe in text) return false
        return text.lines().count { '|' in it } >= 2
    }

    /** Where "Find it" goes: a search for the thing, never the AI's own link. */
    fun searchUrl(s: Suggestion): String {
        val q = java.net.URLEncoder.encode("${s.title} ${s.creator}".trim(), "UTF-8")
        return when (s.kind) {
            Kind.VIDEO -> "https://www.youtube.com/results?search_query=$q"
            Kind.BOOK -> "https://openlibrary.org/search?q=$q"
            Kind.PDF -> "https://www.google.com/search?q=$q+filetype%3Apdf"
            Kind.ARTICLE -> "https://www.google.com/search?q=$q"
        }
    }

    // The saved list, kept as one JSON string.

    fun encode(list: List<Suggestion>): String = JSONArray().also { array ->
        list.forEach { s ->
            array.put(
                JSONObject().put("t", s.title).put("c", s.creator).put("k", s.kind.name)
                    .put("w", s.why).put("l", s.link)
            )
        }
    }.toString()

    fun decode(json: String?): List<Suggestion> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: Kind.ARTICLE
                Suggestion(o.optString("t"), o.optString("c"), kind, o.optString("w"), o.optString("l"))
                    .takeIf { it.title.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Adds to the saved list, skipping what is already there by title. */
    fun add(list: List<Suggestion>, new: List<Suggestion>): List<Suggestion> {
        val have = list.map { it.title.trim().lowercase() }.toMutableSet()
        return list + new.filter { have.add(it.title.trim().lowercase()) }
    }
}
