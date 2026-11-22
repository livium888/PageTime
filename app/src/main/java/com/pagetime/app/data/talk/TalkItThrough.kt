package com.pagetime.app.data.talk

import org.json.JSONObject
import java.util.Random

/**
 * A Socratic conversation about what was just read, held with the reader's own
 * AI app, and the reading time it earns.
 *
 * PageTime never calls a model. It builds the prompt, times the conversation
 * itself (an AI has no clock, so any time it reported would be a guess), and
 * reads a short receipt the AI ends with. Credit is the smallest of three
 * numbers, so a walked-away conversation or an invented receipt cannot earn
 * more than the questions asked could plausibly take. Like the reading guard it
 * is a speed bump, not a wall: nothing can prove who answered.
 */
object TalkItThrough {

    const val SECONDS_PER_EXCHANGE = 90L
    const val CAP_SECONDS = 20L * 60
    /** Faster than this per exchange is a receipt pasted without a conversation. */
    const val MIN_SECONDS_PER_EXCHANGE = 20L

    /** How many core questions a stretch of this many words deserves. */
    fun questionsFor(words: Int): Int = when {
        words < 2_500 -> 1
        words < 8_000 -> 2
        else -> 3
    }

    /** What the sheet says to expect, in minutes. */
    fun estimateMinutes(questions: Int): Int = questions * 3

    const val MAX_PASSAGE_CHARS = 6_000
    private const val MAX_HIGHLIGHTS = 6
    private const val MAX_HIGHLIGHT_CHARS = 140

    /** A short code the AI must echo, so a receipt can be matched to the prompt it answers. */
    fun newCode(random: Random = Random()): String {
        val letters = "abcdefghjkmnpqrstuvwxyz23456789"
        return "talk-" + (1..5).map { letters[random.nextInt(letters.length)] }.joinToString("")
    }

    fun buildPrompt(
        bookTitle: String,
        sectionLabel: String,
        passage: String,
        highlights: List<String>,
        concepts: List<String>,
        questions: Int,
        code: String,
    ): String = buildString {
        appendLine("You are a Socratic tutor. I have just read this part of “${bookTitle.trim()}” ($sectionLabel):")
        appendLine()
        appendLine(excerpt(passage))
        appendLine()
        val marks = highlights.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_HIGHLIGHT_CHARS) }
            .filter { it.isNotEmpty() }.take(MAX_HIGHLIGHTS)
        if (marks.isNotEmpty()) appendLine("My highlights: " + marks.joinToString("; ") { "“$it”" })
        val ideas = concepts.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (ideas.isNotEmpty()) appendLine("Ideas I met: ${ideas.joinToString(", ")}.")
        appendLine()
        appendLine(
            "Ask me $questions core ${if (questions == 1) "question" else "questions"}, ONE at a time, and wait for my " +
                "answer each time. Never give me the answer first. When I answer, probe with a follow-up (why? what " +
                "would change if…? an example?) until my answer is clear and in my own words, or until it is " +
                "clear I do not know it. Use only the passage above, and say so if you are unsure."
        )
        appendLine(com.pagetime.app.data.learning.LearnerStyle.SOCRATIC)
        appendLine(
            "When you are satisfied, or after at most ${questions * 3 + 3} exchanges, finish with exactly this receipt, " +
                "counting each of my answers as one exchange:"
        )
        appendLine()
        appendLine("PAGETIME RECEIPT")
        appendLine("code: $code")
        appendLine("exchanges: <number of my answers>")
        appendLine("verdict: understood | partly | not yet | did not engage")
        append("shaky: <the ideas I was unsure about, separated by semicolons, or none>")
    }

    /** The passage, cut to a size a chat can take, at a sentence end where there is one. */
    fun excerpt(passage: String): String {
        val text = passage.trim()
        if (text.length <= MAX_PASSAGE_CHARS) return text
        val cut = text.take(MAX_PASSAGE_CHARS)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        return (if (end > MAX_PASSAGE_CHARS / 2) cut.take(end + 1) else cut).trim() + " …"
    }

    enum class Verdict(val label: String) {
        UNDERSTOOD("Understood"), PARTLY("Partly"), NOT_YET("Not yet"), DID_NOT_ENGAGE("Did not engage");

        companion object {
            fun parse(word: String): Verdict? {
                val w = word.lowercase().trim()
                return when {
                    "not engage" in w || "disengag" in w -> DID_NOT_ENGAGE
                    "not yet" in w || "not_yet" in w -> NOT_YET
                    "partly" in w || "partial" in w -> PARTLY
                    "understood" in w || "understand" in w -> UNDERSTOOD
                    else -> null
                }
            }
        }
    }

    data class Receipt(val code: String, val exchanges: Int, val verdict: Verdict, val shaky: List<String>)

    /**
     * Reads the receipt from a reply. The code must be the one in the prompt:
     * a receipt for some other conversation, or one with no code, is refused
     * with a way forward.
     */
    fun parseReceipt(reply: String, expectedCode: String): Result<Receipt> {
        fun field(name: String): String? =
            Regex("(?im)^[\\s>*_-]*\\**$name\\**\\s*:\\s*(.+?)\\s*$").find(reply)?.groupValues?.get(1)
                ?.replace("*", "")?.trim()
        val code = field("code")
        if (code == null || !code.contains(expectedCode, ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException(
                    "That does not look like the receipt for this conversation. Ask your AI to finish with the " +
                        "PAGETIME RECEIPT, with code: $expectedCode."
                )
            )
        }
        val exchanges = field("exchanges")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            ?: return Result.failure(IllegalArgumentException("The receipt has no number of exchanges."))
        val verdict = field("verdict")?.let(Verdict::parse)
            ?: return Result.failure(IllegalArgumentException("The receipt has no verdict I can read."))
        val shaky = field("shaky").orEmpty().split(';', '\n')
            .map { it.trim().trim('.') }.filter { it.isNotEmpty() && !it.equals("none", true) }
        return Result.success(Receipt(code, exchanges.coerceIn(0, 60), verdict, shaky))
    }

    /** What the conversation earns, and the three numbers it was worked out from. */
    data class Credit(
        val seconds: Long,
        val stopwatch: Long,
        val byExchanges: Long,
        val cap: Long,
        /** Why nothing was earned, or null. */
        val withheld: String?,
    )

    fun credit(elapsedSeconds: Long, receipt: Receipt): Credit {
        val byExchanges = receipt.exchanges * SECONDS_PER_EXCHANGE
        fun none(why: String) = Credit(0, elapsedSeconds, byExchanges, CAP_SECONDS, why)
        return when {
            receipt.verdict == Verdict.DID_NOT_ENGAGE -> none("Your AI said you did not engage with the questions.")
            receipt.exchanges <= 0 -> none("The receipt counts no answers.")
            elapsedSeconds < receipt.exchanges * MIN_SECONDS_PER_EXCHANGE ->
                none("That was faster than ${receipt.exchanges} answers could be given.")
            else -> Credit(minOf(elapsedSeconds, byExchanges, CAP_SECONDS), elapsedSeconds, byExchanges, CAP_SECONDS, null)
        }
    }

    // A conversation in progress, kept so it survives the app being closed while the AI app is open.

    data class Pending(val code: String, val startedAt: Long, val label: String)

    fun encode(p: Pending): String =
        JSONObject().put("c", p.code).put("t", p.startedAt).put("l", p.label).toString()

    fun decode(raw: String?): Pending? = runCatching {
        val o = JSONObject(raw ?: return null)
        Pending(o.getString("c"), o.getLong("t"), o.optString("l"))
    }.getOrNull()

    /** m:ss for a stopwatch. */
    fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
