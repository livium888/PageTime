package com.pagetime.app.data.learning

import com.pagetime.app.data.LumenCapture

/** A prompt as the model returned it, before anything has been checked. */
data class RawPrompt(
    /** Which supplied passage this came from. */
    val passageIndex: Int,
    val prompt: String,
    val answer: String,
    /** Text the model says it took this from; must be in the passage verbatim. */
    val sourceQuote: String,
    /** "qa" or "cloze". Unknown values are treated as qa. */
    val type: String = TYPE_QA,
) {
    val isCloze: Boolean get() = type.equals(TYPE_CLOZE, ignoreCase = true)

    companion object {
        const val TYPE_QA = "qa"
        const val TYPE_CLOZE = "cloze"
    }
}

/** Why a prompt was thrown away. Named so a rejected batch can be explained. */
enum class PromptRejection(val reason: String) {
    EMPTY("nothing to ask or nothing to answer"),
    UNKNOWN_PASSAGE("cites a passage that was not supplied"),
    QUOTE_NOT_IN_PASSAGE("its quote is not in the passage it claims"),
    NOT_A_QUESTION("is a statement rather than a question"),
    ANSWER_GIVEN_AWAY("contains its own answer"),
    ABOUT_THE_TEXT("asks what the text says rather than what is true"),
    TOO_LONG("is longer than anything anyone recalls"),
    DUPLICATE("repeats a prompt already accepted"),
    ASKS_FOR_A_SET("asks for a list or a set, which cannot be graded honestly"),
    NEAR_DUPLICATE("is a rewording of a prompt already accepted"),
    CLOZE_NOT_IN_PASSAGE("its cloze sentence is not in the passage"),
    CLOZE_MALFORMED("has no deletion in it, or deletes the whole sentence"),
}

data class PromptVerdict(
    val accepted: List<RawPrompt>,
    val rejected: List<Pair<RawPrompt, PromptRejection>>,
)

/**
 * Deciding which generated prompts are fit to be remembered.
 *
 * WHY THIS IS STRICTER THAN IT LOOKS
 *
 * A bad flashcard is not a neutral outcome. A card the reader keeps is a card
 * they will rehearse on a widening schedule for months, so a wrong one is a
 * falsehood installed on purpose and reinforced deliberately. The asymmetry is
 * total: throwing away a good prompt costs one prompt out of several, from a
 * chapter that will produce more; keeping a bad one costs the reader's trust in
 * every other card and possibly their memory of the fact.
 *
 * So every rule here fails toward silence. Fewer cards is the cheap mistake.
 *
 * THE RULE THAT MATTERS MOST
 *
 * The quote has to be in the passage, character for character after
 * whitespace and punctuation are normalised. A model asked for a supporting
 * quote will happily produce a plausible paraphrase, and a paraphrase is
 * exactly what an invented fact looks like. Requiring the literal text is the
 * only check here that cannot be argued with: either those words are in the
 * book or they are not.
 *
 * This is what the dead schema meant by "exact normalized source text returned
 * by Gemini and validated locally". The column has been waiting for this
 * function.
 */
object ChapterPromptRules {

    /** Beyond this a prompt has stopped being a prompt. */
    const val MAX_PROMPT_WORDS = 30

    /**
     * An answer longer than this is not a thing anyone recalls; it is a
     * paragraph the reader will grade themselves generously on because they
     * "sort of" had it. Matuschak's "focused" principle, enforced rather than
     * hoped for.
     */
    const val MAX_ANSWER_WORDS = 40

    /**
     * Prompts need not end in a question mark, but they must ask rather than
     * assert. Kept short and conservative: a rule that costs a good card every
     * time it misfires has to be narrower than the intuition behind it.
     */
    /** A cloze sentence may be longer than a question; it is still one sentence. */
    const val MAX_CLOZE_WORDS = 60

    private val CLOZE = Regex("""\{\{c\d+::(.+?)\}\}""")

    /**
     * Asking for a set or an enumeration.
     *
     * Deliberately narrow, and count-based rather than verb-based: "Name the
     * reason the system collapsed" is a fine single-answer prompt and must
     * survive, while "Name the three reasons" must not. A check that costs a
     * good card every time it misfires has to be narrower than the intuition
     * behind it.
     */
    private val SET_QUESTION = Regex(
        "\\b(?:name|list|give|state|identify|describe)\\s+(?:me\\s+)?" +
            "(?:all|both|each)\\b" +
            "|\\b(?:name|list|give|state|identify|describe)\\s+(?:the\\s+)?" +
            "(?:two|three|four|five|six|seven|eight|nine|ten|\\d+)\\b" +
            "|\\bwhat\\s+are\\s+the\\b" +
            "|\\bwhich\\s+of\\s+the\\s+following\\b" +
            "|\\blist\\s+the\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun asksForASet(prompt: String): Boolean = SET_QUESTION.containsMatchIn(prompt)

    private val IMPERATIVE_OPENERS = setOf(
        "name", "list", "describe", "explain", "state", "give", "define",
        "identify", "recall", "summarise", "summarize",
    )

    fun check(raw: RawPrompt, passages: List<String>): PromptRejection? {
        val prompt = raw.prompt.trim()
        val answer = raw.answer.trim()
        val quote = raw.sourceQuote.trim()

        if (prompt.isBlank() || answer.isBlank()) return PromptRejection.EMPTY
        val passage = passages.getOrNull(raw.passageIndex) ?: return PromptRejection.UNKNOWN_PASSAGE

        if (quote.isBlank() || !containsQuote(passage, quote)) {
            return PromptRejection.QUOTE_NOT_IN_PASSAGE
        }
        // Wozniak's rule against sets and enumerations. Partial knowledge of a
        // set cannot be graded honestly: the reader half-remembers, grades in
        // the middle, and every fact in the set gets an interval that suits
        // none of them.
        if (asksForASet(prompt)) return PromptRejection.ASKS_FOR_A_SET

        if (raw.isCloze) return checkCloze(prompt, answer, passage)

        if (!asksSomething(prompt)) return PromptRejection.NOT_A_QUESTION
        // The answer sitting inside the question is the giveaway that makes a
        // card feel easy and teach nothing.
        if (containsWords(prompt, answer)) return PromptRejection.ANSWER_GIVEN_AWAY
        // "What does this passage say about X?" is a question about a text, not
        // about the world, and it is worthless the moment the text is closed.
        if (LumenCapture.mentionsSource(prompt)) return PromptRejection.ABOUT_THE_TEXT
        if (words(prompt) > MAX_PROMPT_WORDS || words(answer) > MAX_ANSWER_WORDS) {
            return PromptRejection.TOO_LONG
        }
        return null
    }

    /**
     * A cloze is checked by putting the sentence back together.
     *
     * This is the strongest verification available anywhere in the pipeline. A
     * question and answer can only be checked against a quote the model chose;
     * a cloze IS the sentence, so filling the deletion back in must reproduce
     * text that is literally in the book. A model cannot invent a fact and
     * survive it.
     */
    private fun checkCloze(prompt: String, answer: String, passage: String): PromptRejection? {
        val deletions = CLOZE.findAll(prompt).map { it.groupValues[1] }.toList()
        if (deletions.isEmpty()) return PromptRejection.CLOZE_MALFORMED

        val restored = CLOZE.replace(prompt) { it.groupValues[1] }.trim()
        if (restored.isBlank()) return PromptRejection.CLOZE_MALFORMED
        // Deleting the whole sentence leaves nothing to remember it from.
        if (normalize(deletions.joinToString(" ")) == normalize(restored)) {
            return PromptRejection.CLOZE_MALFORMED
        }
        if (!containsWords(passage, restored)) return PromptRejection.CLOZE_NOT_IN_PASSAGE
        // The stated answer has to be what was actually deleted, or the review
        // screen shows one thing and grades another.
        if (deletions.none { normalize(it) == normalize(answer) }) {
            return PromptRejection.CLOZE_MALFORMED
        }
        if (words(restored) > MAX_CLOZE_WORDS) return PromptRejection.TOO_LONG
        return null
    }

    /**
     * How alike two prompts may be before the second is a rewording.
     *
     * Jaccard overlap of their words. Calibrated against real pairs rather
     * than chosen for roundness:
     *
     *   "What year did Napoleon invade Russia?" against
     *   "In what year did Napoleon invade Russia?" scores 0.86 — one prompt,
     *   written twice, and it must go.
     *
     *   "Why did the Continental System fail?" against
     *   "What did the Continental System ban?" scores 0.50 — two facts about
     *   one subject, which is exactly what asking for several prompts per
     *   passage is meant to produce, and it must survive.
     *
     * Both are tested. The gap between them is wide, which is the only reason
     * a single threshold is defensible here.
     */
    const val NEAR_DUPLICATE_SIMILARITY = 0.8f

    /**
     * Checks a whole batch, dropping repeats after the first of their kind.
     *
     * Convenience for a single batch. A chapter is generated in several
     * requests, and duplicates across those requests are exactly as bad as
     * duplicates within one, so the generator uses [PromptSifter] and keeps it
     * for the whole chapter.
     */
    fun sift(raws: List<RawPrompt>, passages: List<String>): PromptVerdict =
        PromptSifter().sift(raws, passages)

    /**
     * Whether two prompts are the same question wearing different words.
     *
     * Exposed for the sifter and for its tests; the interesting behaviour is
     * the threshold, not the arithmetic.
     */
    fun similarity(a: String, b: String): Float {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0f
        val shared = left.count { it in right }
        val union = left.size + right.size - shared
        return if (union == 0) 0f else shared.toFloat() / union
    }

    internal fun tokens(text: String): Set<String> =
        normalize(text).split(' ').filter { it.isNotBlank() }.toSet()

    /** The form two prompts must share to count as literally the same. */
    internal fun exactKey(text: String): String = normalize(text)

    /**
     * Whether [quote] appears in [passage] once both are reduced to their
     * words.
     *
     * Normalising is not laxness: a model reflows whitespace, converts straight
     * quotes to curly ones and hyphens to dashes, and none of those change
     * whether the sentence is in the book. What it must not be allowed to do is
     * change a word.
     *
     * A leading or trailing ellipsis is stripped first, because quoting an
     * excerpt that way is honest rather than invented.
     */
    fun containsQuote(passage: String, quote: String): Boolean {
        val needle = quote.trim().trim('…').trim('.', ' ')
        if (normalize(needle).isBlank()) return false
        return containsWords(passage, needle)
    }

    /**
     * Whether [needle] appears in [haystack] as whole words.
     *
     * Padding with spaces rather than a plain substring test, because
     * "napoleon" contains "no": a one-word answer would otherwise be found
     * inside an unrelated longer word and the card thrown away for giving
     * itself away.
     */
    private fun containsWords(haystack: String, needle: String): Boolean {
        val inner = normalize(needle)
        if (inner.isBlank()) return false
        return " ${normalize(haystack)} ".contains(" $inner ")
    }

    private fun asksSomething(prompt: String): Boolean {
        if (prompt.contains('?')) return true
        val first = prompt.trimStart().takeWhile { it.isLetter() }.lowercase()
        return first in IMPERATIVE_OPENERS
    }

    private fun words(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /**
     * Casefolded, with everything that is not a letter or a digit reduced to a
     * single space.
     *
     * That one step folds curly quotes, dashes, non-breaking spaces and stray
     * punctuation together, which is the point: a model reflows whitespace and
     * converts straight quotes to curly ones, and none of that changes whether
     * the sentence is in the book. What it must not be allowed to change is a
     * word, and none of this touches one.
     */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
}

/**
 * Sifting a whole chapter, across however many requests it took.
 *
 * WHY THIS HOLDS STATE
 *
 * A chapter is no longer one request. At Quantum Country's density a long
 * chapter sends two dozen passages, which is more than one call should carry,
 * so it is split into batches. A duplicate that arrives in batch three is
 * exactly as bad as one that arrives in batch one — worse, if anything,
 * because nothing later will catch it — and a sifter created fresh per batch
 * cannot see it. So the state lives for the chapter.
 *
 * WHY NEAR-DUPLICATES ONLY BECAME WORTH CATCHING NOW
 *
 * At four prompts a chapter, two similar ones are a minor annoyance. At fifty,
 * asking for several prompts from each passage, rewordings are the predictable
 * failure: the model is being invited to find three things to say about a
 * paragraph that may only support two, and the cheapest way to comply is to
 * ask the same question twice.
 *
 * That is Wozniak's interference rule, and it is the one place where raising
 * the density genuinely does make the cards worse unless something is done
 * about it. This is the something.
 */
class PromptSifter {

    private val seen = HashSet<String>()
    private val acceptedTokens = mutableListOf<Set<String>>()

    fun sift(raws: List<RawPrompt>, passages: List<String>): PromptVerdict {
        val accepted = mutableListOf<RawPrompt>()
        val rejected = mutableListOf<Pair<RawPrompt, PromptRejection>>()

        for (raw in raws) {
            val failure = ChapterPromptRules.check(raw, passages)
            if (failure != null) {
                rejected += raw to failure
                continue
            }
            // Two passages about one idea produce one question twice, and the
            // reader meets it as two cards that are the same work.
            if (!seen.add(ChapterPromptRules.exactKey(raw.prompt))) {
                rejected += raw to PromptRejection.DUPLICATE
                continue
            }
            val tokens = ChapterPromptRules.tokens(raw.prompt)
            if (acceptedTokens.any { jaccard(tokens, it) >= ChapterPromptRules.NEAR_DUPLICATE_SIMILARITY }) {
                rejected += raw to PromptRejection.NEAR_DUPLICATE
                continue
            }
            acceptedTokens += tokens
            accepted += raw
        }
        return PromptVerdict(accepted, rejected)
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val shared = a.count { it in b }
        val union = a.size + b.size - shared
        return if (union == 0) 0f else shared.toFloat() / union
    }
}
