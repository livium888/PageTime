package com.pagetime.app.data.embed

import com.pagetime.app.data.local.BookChunkEmbeddingEntity

/**
 * One idea a chapter spends time on, and the passage that states it best.
 *
 * [centrality] is how close this passage sits to the chapter's centre of mass —
 * useful for showing the reader why it was picked, and for cutting a chapter
 * that turns out to have only one idea in it.
 */
data class TopicPassage(
    val chapterIndex: Int,
    val ordinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val centrality: Float,
    /** Length of the chapter, so an offset can become a position. */
    val chapterChars: Int = 0,
) {
    /** Where the passage begins, as a fraction of the chapter. */
    val startProgression: Float
        get() = fraction(startOffset)

    /**
     * Where the passage ENDS.
     *
     * This is the one that matters for surfacing a prompt: asking a question
     * as the reader arrives at the paragraph is asking it before they have
     * read the answer. Waiting for the end needs no guessed margin — the
     * chunk boundaries already say exactly where the idea finishes.
     */
    val endProgression: Float
        get() = fraction(endOffset)

    private fun fraction(offset: Int): Float =
        if (chapterChars > 0) (offset.toFloat() / chapterChars).coerceIn(0f, 1f) else 0f
}

/**
 * Choosing what a chapter is about, so the model is told rather than asked.
 *
 * WHY NOT THE MOST FREQUENT WORDS
 *
 * The obvious approach is to count words, drop the stop words, and take the
 * top five. It finds the wrong thing. The most frequent content word in a
 * chapter is its SUBJECT, not its ideas: a chapter about Napoleon says
 * "Napoleon" eighty times, which is a label, and a card needs a claim. Prose
 * repeats what is easy to repeat — names, the recurring noun, the running
 * example — while the sentence that matters is usually stated once, precisely,
 * and referred to by pronoun thereafter. Counting words systematically misses
 * it.
 *
 * There is a second cost. A prompt built around a chapter's most-repeated term
 * invites the reader to recognise the term rather than recall the claim, which
 * is the failure that makes flashcards feel like they are working while
 * teaching nothing.
 *
 * WHAT THIS DOES INSTEAD
 *
 * The chapter's chunks are already vectors, which describe what the text is
 * ABOUT rather than which words it used. So the chapter's centre of mass is
 * the average of them, and the passages nearest that centre are the passages
 * most on-topic.
 *
 * Nearest-to-centre alone would return five paragraphs about the same thing,
 * so selection is greedy [maximal marginal relevance]: each pick is scored on
 * how central it is MINUS how much it resembles what has already been picked.
 * Five picks, five different ideas.
 *
 * MMR RATHER THAN K-MEANS
 *
 * k-means was the other candidate and would work. MMR wins on three counts
 * that matter here: it is deterministic, so a reader who reopens a chapter
 * gets the same prompts rather than a new set from a different random
 * initialisation; it optimises the thing actually wanted (central but
 * distinct) rather than cluster compactness, which is only a proxy for it; and
 * it has no iteration to converge, so it cannot behave differently on a
 * chapter that happens to be awkward.
 *
 * THE OFFSETS ARE THE POINT
 *
 * Each passage carries where it sits in the chapter, so a prompt built from it
 * can surface at that spot while the reader is reading — the idea is fresh,
 * and nothing had to be computed twice to know where fresh is.
 */
object ChapterTopics {

    /**
     * How much of the score is centrality; the rest is being unlike what is
     * already chosen.
     *
     * At 1.0 this degenerates into "five paragraphs nearest the centre", which
     * are five paragraphs about one idea. At 0.0 it picks the five most
     * mutually alien passages in the chapter, which are its digressions. 0.7
     * leans toward on-topic while still refusing near-duplicates — a first
     * estimate, and the honest thing to say about it is that the number that
     * belongs here comes from looking at real chapters.
     */
    const val LAMBDA = 0.7f

    /**
     * One passage per ~400 words, which is Quantum Country's own spacing.
     *
     * Orbit's author documentation describes review areas "interleaved every
     * few hundred words", each holding several prompts. This used to be 9,000
     * characters — one passage per 1,500 words, about a fifth of their
     * density — on the reasoning that their prompts are handwritten and ours
     * are generated, so we should claim less.
     *
     * That reasoning was sound and the conclusion was still wrong, because a
     * medium this sparse cannot be evaluated. Four cards from a chapter is a
     * rounding error on what the reader remembers, so whether the feature
     * works is unanswerable either way. Testing it at the density it is meant
     * to be used at is the only way to find out.
     *
     * The quality worry does not go away; it moves. It is now carried by the
     * rules that throw prompts out, which is where it can actually be
     * enforced, rather than by refusing to generate them.
     */
    const val CHARS_PER_TOPIC = 2_500

    const val MIN_TOPICS = 4

    /**
     * A ceiling, not a target.
     *
     * At three prompts per passage this allows 72 from one chapter, which is
     * already more than anyone will accept in a sitting. It exists to stop a
     * single enormous chapter — an unsplit plain-text book, say — from
     * spending the reader's whole quota in one tap.
     */
    const val MAX_TOPICS = 24

    /**
     * How many prompts to ask for from each passage.
     *
     * Quantum Country's review areas hold several prompts each, and Wozniak is
     * explicit that approaching one idea from several angles does not breach
     * the minimum information principle. Three is an upper bound the model is
     * told it may ignore; a passage that carries one idea should return one.
     */
    const val PROMPTS_PER_PASSAGE = 3

    /** How many passages a chapter of [chars] characters is worth. */
    fun countFor(chars: Int): Int =
        (chars / CHARS_PER_TOPIC).coerceIn(MIN_TOPICS, MAX_TOPICS)

    /**
     * The most prompts a chapter of [chars] characters could yield.
     *
     * Reported to the reader before generating, because at this density one
     * tap is a materially larger API call than it used to be and they should
     * know that before making it.
     */
    fun promptCeilingFor(chars: Int): Int = countFor(chars) * PROMPTS_PER_PASSAGE

    /**
     * The passages worth building prompts from, in reading order.
     *
     * [rows] is one chapter's chunks. Returns fewer than [count] when the
     * chapter does not have that many distinct things to say, which is the
     * honest answer for a title page or a two-paragraph interlude.
     */
    fun select(
        rows: List<BookChunkEmbeddingEntity>,
        count: Int = 0,
        lambda: Float = LAMBDA,
    ): List<TopicPassage> {
        if (rows.isEmpty()) return emptyList()

        // A chapter's rows all come from one model, so one width. A row of
        // another width cannot be averaged in or compared, and doing it anyway
        // is the silent wrongness the model column exists to stop.
        val width = rows.groupingBy { it.dimensions }.eachCount()
            .maxByOrNull { it.value }?.key ?: return emptyList()
        val usable = rows.filter { it.dimensions == width && it.vector.size == width * 4 }
        if (usable.isEmpty()) return emptyList()

        val vectors = usable.map { EmbeddingMath.fromBytes(it.vector) }
        val centroid = EmbeddingMath.l2Normalize(mean(vectors, width))

        val chapterChars = usable.maxOf { it.endOffset }
        val wanted = (if (count > 0) count else countFor(chapterChars)).coerceAtMost(usable.size)

        val centrality = FloatArray(usable.size) { EmbeddingMath.cosineSimilarity(centroid, vectors[it]) }

        // Running state rather than a rescan of the chosen list each round.
        //
        // The original recomputed every candidate's resemblance to every
        // passage already picked, on every round: fine at five picks, and
        // O(picks squared * chunks) vector comparisons, which at
        // twenty-four picks over a long chapter is hundreds of thousands of
        // 384-dimensional dot products on a phone while the reader waits.
        //
        // Resemblance to a SET is the maximum over its members, and a maximum
        // only ever needs the new member, so carrying it forward costs one
        // pass per round and gives identical results.
        val taken = BooleanArray(usable.size)
        val blocked = BooleanArray(usable.size)
        val redundancy = FloatArray(usable.size)

        val chosen = mutableListOf<Int>()
        while (chosen.size < wanted) {
            var best = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in usable.indices) {
                if (taken[i] || blocked[i]) continue
                val score = lambda * centrality[i] - (1f - lambda) * redundancy[i]
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best < 0) break
            taken[best] = true
            chosen += best

            for (i in usable.indices) {
                if (taken[i] || blocked[i]) continue
                // Chunks overlap by design, so the neighbour of a chosen
                // passage is largely the same text. Excluded by position as
                // well as by similarity: two chunks sharing 80 characters are
                // one idea however their vectors happen to land.
                if (overlaps(usable[best], usable[i])) {
                    blocked[i] = true
                    continue
                }
                val similarity = EmbeddingMath.cosineSimilarity(vectors[best], vectors[i])
                if (similarity > redundancy[i]) redundancy[i] = similarity
            }
        }

        // Selected by importance, returned by position: a prompt belongs where
        // its idea is, and the reader meets them in the order the chapter
        // makes them.
        return chosen
            .map { i ->
                val row = usable[i]
                TopicPassage(
                    chapterIndex = row.chapterIndex,
                    ordinal = row.ordinal,
                    startOffset = row.startOffset,
                    endOffset = row.endOffset,
                    text = row.text,
                    centrality = centrality[i],
                    chapterChars = chapterChars,
                )
            }
            .sortedBy { it.startOffset }
    }

    private fun overlaps(a: BookChunkEmbeddingEntity, b: BookChunkEmbeddingEntity): Boolean =
        a.chapterIndex == b.chapterIndex &&
            a.startOffset < b.endOffset &&
            b.startOffset < a.endOffset

    private fun mean(vectors: List<FloatArray>, width: Int): FloatArray {
        val sum = FloatArray(width)
        for (vector in vectors) {
            for (d in 0 until width) sum[d] += vector[d]
        }
        val divisor = vectors.size.toFloat()
        for (d in 0 until width) sum[d] = sum[d] / divisor
        return sum
    }
}
