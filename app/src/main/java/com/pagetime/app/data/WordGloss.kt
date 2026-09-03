package com.pagetime.app.data

/**
 * What a word means, and what it means *here*.
 *
 * Two questions, because two readers ask them. Someone still learning English
 * needs the plain meaning first — they do not know the word at all, and being
 * told "in this sentence it is the approval sense" helps nobody who has never
 * met the word. Someone fluent knows the word and wants to know which of its
 * senses is on the page. Answering only the second was a design for the second
 * reader alone.
 *
 * The four fields exist because a small model fills named slots far more
 * reliably than it follows instructions about what to include — the same
 * lesson the card's "idea" and "because" fields taught. Asking for a
 * definition in prose got a definition sometimes; asking for a field called
 * "meaning" gets one.
 *
 * Etymology and origins stay banned. They are recalled facts rather than
 * anything the passage supports, they are where a small model invents most
 * confidently, and a reader cannot catch a wrong one. Everything asked for
 * here is either grounded in the passage or is ordinary vocabulary a model
 * this size knows well.
 */
object WordGloss {

    /** Longest selection worth explaining; beyond this it is a passage, not a term. */
    const val MAX_TERM_CHARS = 120

    /** Context kept either side. Readium hands back about 200 characters each way. */
    const val MAX_CONTEXT_CHARS = 240

    /** Longest any single field is kept, so a rambling reply cannot fill the screen. */
    const val MAX_FIELD_CHARS = 300

    /**
     * The selected text as a word to look up.
     *
     * A reader dragging over a word routinely takes the punctuation with it —
     * a comma, a closing quote, the full stop after it. Those characters reach
     * the prompt as part of the word and reach the example check as characters
     * no sentence will contain, so they come off first.
     */
    fun cleanTerm(term: String): String =
        normalize(term).trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }

    /**
     * Why [term] cannot be explained, or null when it can. A selection spanning
     * a paragraph is a capture, not a word to look up.
     */
    fun termProblem(term: String): String? {
        val trimmed = cleanTerm(term)
        return when {
            trimmed.isBlank() -> "Nothing is selected."
            trimmed.length > MAX_TERM_CHARS ->
                "That is a passage rather than a term — capture it as a card instead."
            else -> null
        }
    }

    /**
     * The sentence the term sits in, rebuilt from the selection's surroundings,
     * for showing beside the answer. The reader can then check the explanation
     * against the words it claims to be explaining, which is the whole defence
     * against a confident wrong answer.
     */
    fun sentenceAround(before: String, term: String, after: String): String {
        val lead = before.takeLast(MAX_CONTEXT_CHARS).substringAfterLast(". ").trimStart()
        val tail = after.take(MAX_CONTEXT_CHARS).let { rest ->
            val stop = rest.indexOfFirst { it == '.' || it == '!' || it == '?' }
            if (stop >= 0) rest.take(stop + 1) else rest
        }
        return normalize("$lead$term$tail")
    }

    fun prompt(term: String, before: String, after: String, bookTitle: String): String {
        val word = cleanTerm(term)
        val context = normalize(
            before.takeLast(MAX_CONTEXT_CHARS) + " ⟦" + word + "⟧ " + after.take(MAX_CONTEXT_CHARS)
        )
        return """
            |Book: "$bookTitle"
            |
            |Passage, with the reader's selection marked ⟦like this⟧:
            |$context
            |
            |The reader does not know the word ⟦$word⟧. They may still be
            |learning English, so explain it the way you would to someone who
            |has never met the word.
            |
            |Fill in these four fields:
            |- kind: the part of speech here — noun, verb, adjective, adverb,
            |  phrase. One word.
            |- meaning: what the word means in general, in ONE short sentence.
            |  Use the simplest words you can. Someone reading your explanation
            |  must not need a second dictionary to understand it.
            |- here: what it means in THIS passage, in one sentence. If the
            |  passage does not make the sense clear, say so plainly.
            |- example: one short, everyday sentence that contains the word
            |  "$word" itself, used in the same sense. Your own sentence, not
            |  one from the passage.
            |
            |Rules:
            |- Never give etymology, origins, or which language it came from.
            |- Never list several senses. The general meaning, then this one.
            |- Plain words throughout. Short sentences.
            |- The example sentence has to contain the word "$word". A sentence
            |  built out of the words of your explanation instead is wrong.
            |
            |Example:
            |Word: ⟦sanction⟧ in "the council voted to sanction the new library"
            |{"kind": "verb", "meaning": "To officially allow or approve
            |something.", "here": "The council formally gave permission for the
            |library to go ahead.", "example": "The school sanctioned a trip to
            |the museum."}
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"kind": "...", "meaning": "...", "here": "...", "example": "a
            |sentence using $word"}
            |{"kind": "
            """.trimMargin()
    }

    /**
     * The model's reply as fields. Falls back to treating the whole reply as
     * the meaning: a model that answered in prose still answered, and dropping
     * that on the floor would leave the reader with nothing over a formatting
     * problem.
     */
    fun parse(raw: String, term: String): GlossParts? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isBlank()) return null

        // Whether the reply is shaped like the answer, not whether the fields
        // came back filled. A model that returns {"meaning":""} answered in the
        // right shape and said nothing; treating that as prose would hand the
        // reader the JSON punctuation as a definition.
        val answered = FIELDS.any { LumenCapture.jsonStringValue(cleaned, it, truncated = true) != null }
        if (answered) {
            return GlossParts(
                kind = field(cleaned, "kind")?.lowercase()?.take(24),
                meaning = field(cleaned, "meaning"),
                here = field(cleaned, "here"),
                example = field(cleaned, "example")?.takeIf { usesTerm(it, term) },
            )
        }
        // No recognisable fields at all: the whole reply is the answer it managed.
        val prose = clean(cleaned.trim('{', '}', '"'))
        return prose?.let { GlossParts(kind = null, meaning = it, here = null, example = null) }
    }

    /**
     * True when [sentence] actually uses [term], allowing for its endings —
     * "sanction" is used by "The school sanctioned a trip".
     *
     * The example is the field a small model gets wrong in a specific way: by
     * the time it reaches the last slot, the nearest text is the explanation it
     * has just written, so it continues from that instead of going back to the
     * word. What comes out is a fluent sentence built from the words of the
     * definition, with the word itself nowhere in it — an example of the
     * meaning rather than of the word.
     *
     * An example that does not contain the word is not a weaker example, it is
     * the wrong thing, so it is dropped rather than shown. It is not re-asked:
     * the whole answer takes the better part of twenty seconds, and doubling
     * that for the fourth-most-important field would be a poor trade. The
     * prompt now names the word twice to make the miss rarer, and this catches
     * what still gets through.
     *
     * A phrase is judged on its longest word, which is the one carrying its
     * meaning and the one an example is least likely to omit by accident.
     */
    fun usesTerm(sentence: String, term: String): Boolean {
        val word = cleanTerm(term).lowercase()
        if (word.isBlank()) return true
        val text = sentence.lowercase()
        if (beginsAWordIn(text, word)) return true
        val head = word.split(' ', '-').maxByOrNull { it.length }?.takeIf { it.length >= 3 }
            ?: return false
        return WORDS.findAll(text).any { sharesStem(it.value, head) }
    }

    /**
     * True when [word] appears in [text] at the start of a word.
     *
     * Only the left edge is checked, because the right edge is where the
     * endings live: "sanctioned" has to count as using "sanction". The left
     * edge is what stops "the fact remains" from counting as an example of
     * "act".
     */
    private fun beginsAWordIn(text: String, word: String): Boolean {
        var at = text.indexOf(word)
        while (at >= 0) {
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }

    /**
     * Whether two words are forms of each other. One has to be a whole prefix
     * of the other and within four characters of it, so "sanction" matches
     * "sanctioned" but not "sanctify" — which shares six letters and is a
     * different word.
     */
    private fun sharesStem(a: String, b: String): Boolean {
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length < 3 || longer.length - shorter.length > 4) return false
        return longer.startsWith(shorter)
    }

    private val WORDS = Regex("""[\p{L}\p{N}'-]+""")

    private val FIELDS = listOf("kind", "meaning", "here", "example")

    private fun field(source: String, key: String): String? =
        clean(LumenCapture.jsonStringValue(source, key, truncated = true).orEmpty())

    private fun clean(value: String): String? {
        var text = normalize(value).trim().trim('"', '\'')
        LEAD_INS.forEach { lead ->
            if (text.startsWith(lead, ignoreCase = true)) {
                text = text.removeRange(0, lead.length).trimStart(' ', ':', ',', '-', '—')
            }
        }
        if (text.isBlank()) return null
        text = text.replaceFirstChar { it.uppercaseChar() }
        if (text.length <= MAX_FIELD_CHARS) return text
        val cut = text.take(MAX_FIELD_CHARS).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (cut > MAX_FIELD_CHARS / 2) text.take(cut + 1) else text.take(MAX_FIELD_CHARS).trimEnd() + "…"
    }

    private val LEAD_INS = listOf(
        "Sure!", "Sure,", "Certainly!", "Certainly,", "Of course!",
        "Here is the explanation", "Here's the explanation",
        "In this passage,", "In this context,",
    )

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}

/** The fields a reply managed to fill. Any of them may be missing. */
data class GlossParts(
    val kind: String?,
    val meaning: String?,
    val here: String?,
    val example: String?,
) {
    val isEmpty: Boolean get() = meaning == null && here == null && example == null
}

/** An explanation of a selected term, with the sentence it was read in. */
data class Gloss(
    val term: String,
    val sentence: String,
    val parts: GlossParts,
    val source: LlmProviderKind?,
)
