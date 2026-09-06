package com.pagetime.app.data.embed

import java.io.BufferedReader
import java.io.InputStream
import java.text.Normalizer

/**
 * The token table a WordPiece model was trained against.
 *
 * A vocabulary file is one token per line and the line number IS the id, so
 * order is the whole contract — a file read out of order produces ids that
 * index the wrong rows of the model's embedding matrix and yields plausible
 * nonsense rather than an error. Hence [fromLines] preserving order and
 * refusing duplicates rather than silently keeping the last one.
 */
class WordPieceVocabulary private constructor(
    private val tokenToId: Map<String, Int>,
) {
    val size: Int get() = tokenToId.size

    fun id(token: String): Int? = tokenToId[token]

    /** Id for [token], or the unknown-token id. */
    fun idOrUnknown(token: String, unknownId: Int): Int = tokenToId[token] ?: unknownId

    companion object {
        fun fromLines(lines: Sequence<String>): WordPieceVocabulary {
            val map = LinkedHashMap<String, Int>()
            lines.forEachIndexed { index, raw ->
                // Only the line separator is stripped. A vocabulary can contain
                // tokens that are themselves whitespace or punctuation, and
                // trimming them would shift every id after that line.
                val token = raw.removeSuffix("\r")
                if (token.isNotEmpty()) map.putIfAbsent(token, index)
            }
            require(map.isNotEmpty()) { "The vocabulary file is empty." }
            return WordPieceVocabulary(map)
        }

        fun fromStream(input: InputStream): WordPieceVocabulary =
            input.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
                fromLines(reader.lineSequence())
            }
    }
}

/** What a model actually consumes: parallel arrays of the same length. */
data class TokenizedText(
    val ids: List<Int>,
    val attentionMask: List<Int>,
    val tokenTypeIds: List<Int>,
) {
    val length: Int get() = ids.size
}

/**
 * BERT-style WordPiece tokenisation, in Kotlin, matching what the reference
 * implementation does so that ids line up with the weights.
 *
 * This exists because an ONNX embedding model takes token ids, not text, and
 * nothing on Android supplies them. It is the unglamorous half of on-device
 * retrieval and the half most likely to be wrong in a way that never throws:
 * a subtly different normalisation produces valid-looking ids for the wrong
 * tokens, the vectors come out slightly off, and similarity search quietly
 * returns worse neighbours forever. So each step below mirrors a specific step
 * of the original rather than doing something equivalent-looking.
 *
 * Two stages, as in the original:
 *
 *  1. Basic tokenisation — clean, optionally lowercase and strip accents,
 *     split on whitespace, then split punctuation off as its own token.
 *  2. WordPiece — greedy longest-match-first within each word, continuation
 *     pieces prefixed with "##", the whole word becoming [UNK] if any position
 *     fails to match.
 */
class WordPieceTokenizer(
    private val vocabulary: WordPieceVocabulary,
    /** Uncased models fold case and strip accents; cased models must not. */
    private val lowercase: Boolean = true,
    private val unknownToken: String = "[UNK]",
    private val classificationToken: String = "[CLS]",
    private val separatorToken: String = "[SEP]",
    private val paddingToken: String = "[PAD]",
    /** Longer than this and the reference implementation gives up on the word. */
    private val maxInputCharsPerWord: Int = 100,
) {
    private val unknownId = requireToken(unknownToken)
    private val classificationId = requireToken(classificationToken)
    private val separatorId = requireToken(separatorToken)
    private val paddingId = requireToken(paddingToken)

    private fun requireToken(token: String): Int =
        vocabulary.id(token)
            ?: throw IllegalArgumentException(
                "The vocabulary has no $token. It does not match this tokeniser — " +
                    "check the file belongs to the model being loaded."
            )

    /** The word pieces of [text], without any special tokens. */
    fun tokenize(text: String): List<String> =
        basicTokenize(text).flatMap { wordPieces(it) }

    /**
     * [text] as model input: [CLS] pieces [SEP], truncated to [maxLength] and
     * padded out to it, with the mask marking which positions are real.
     *
     * Truncation counts the two special tokens, because they are what the model
     * was trained to see at the ends; dropping them to fit more words would
     * change the shape of the input rather than merely shorten it.
     */
    fun encode(text: String, maxLength: Int): TokenizedText {
        require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]." }
        val pieces = tokenize(text).take(maxLength - 2)
        val ids = ArrayList<Int>(maxLength)
        ids += classificationId
        pieces.forEach { ids += vocabulary.idOrUnknown(it, unknownId) }
        ids += separatorId

        val real = ids.size
        val mask = ArrayList<Int>(maxLength).apply {
            repeat(real) { add(1) }
        }
        while (ids.size < maxLength) {
            ids += paddingId
            mask += 0
        }
        return TokenizedText(
            ids = ids,
            attentionMask = mask,
            // Single-sequence input, so every position belongs to segment zero.
            tokenTypeIds = List(maxLength) { 0 },
        )
    }

    /**
     * Stage one: whitespace-and-punctuation splitting, after normalisation.
     */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = buildString(text.length) {
            for (ch in text) {
                val code = ch.code
                // The reference drops NUL and the replacement character, and
                // treats every control character as nothing rather than as a
                // separator.
                if (code == 0 || code == 0xFFFD || isControl(ch)) continue
                if (ch.isWhitespace()) append(' ') else append(ch)
            }
        }
        return cleaned.split(' ')
            .filter { it.isNotEmpty() }
            .flatMap { token ->
                val folded = if (lowercase) stripAccents(token.lowercase()) else token
                splitPunctuation(folded)
            }
            .filter { it.isNotEmpty() }
    }

    /**
     * Accents are removed AFTER lowercasing and only for uncased models, by
     * decomposing and dropping the combining marks. Done with the same
     * decomposition the reference uses rather than a character table, so
     * scripts nobody thought to enumerate behave correctly too.
     */
    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
    }

    /** Every punctuation character becomes a token of its own. */
    private fun splitPunctuation(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in token) {
            if (isPunctuation(ch)) {
                if (current.isNotEmpty()) {
                    out += current.toString()
                    current.setLength(0)
                }
                out += ch.toString()
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /**
     * Stage two: the greedy longest-match-first walk.
     *
     * Starting at the front of the word, take the longest substring present in
     * the vocabulary; every piece after the first is looked up with "##" in
     * front of it. If any position has no match at all, the ENTIRE word becomes
     * unknown — not just the piece that failed, which is the detail most
     * re-implementations get wrong.
     */
    private fun wordPieces(word: String): List<String> {
        if (word.length > maxInputCharsPerWord) return listOf(unknownToken)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: String? = null
            while (start < end) {
                val candidate = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }
                if (vocabulary.id(candidate) != null) {
                    match = candidate
                    break
                }
                end--
            }
            if (match == null) return listOf(unknownToken)
            pieces += match
            start = end
        }
        return pieces
    }

    private fun isControl(ch: Char): Boolean {
        // Tab, newline and carriage return are whitespace to the reference
        // implementation, not control characters, and are handled above.
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        return when (Character.getType(ch).toByte()) {
            Character.CONTROL, Character.FORMAT,
            Character.PRIVATE_USE, Character.SURROGATE,
            Character.UNASSIGNED -> true
            else -> false
        }
    }

    /**
     * The reference treats the ASCII symbol ranges as punctuation even though
     * Unicode files several of them (`$`, `+`, `^`, `` ` ``, `|`, `~`) as
     * symbols rather than punctuation. Following Unicode alone here would split
     * differently and shift the ids.
     */
    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(ch).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }
}
