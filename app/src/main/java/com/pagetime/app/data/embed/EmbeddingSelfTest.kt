package com.pagetime.app.data.embed

/**
 * Ten seconds that decide whether on-device retrieval is worth anything.
 *
 * Every piece below this — the tokeniser, the masked pooling, the
 * normalisation, the ONNX session — has been proven on its own, against
 * fixtures and stub arrays. None of it has ever met real weights. And the
 * failures they guard against do not throw: a vocabulary off by one row, a
 * pooling that averaged the padding, an export that pools its own output and
 * gets pooled again. Every one of those still returns a vector of the right
 * shape, and similarity still returns a number. The slip box just quietly
 * suggests worse neighbours forever.
 *
 * So the chain gets tested end to end, on the reader's own phone, against text
 * whose answer is known in advance.
 */
object EmbeddingSelfTest {

    /** Two texts and whether a working embedder should call them close. */
    data class Probe(val label: String, val first: String, val second: String, val related: Boolean)

    data class Measurement(val label: String, val similarity: Float, val related: Boolean)

    data class Report(
        val measurements: List<Measurement>,
        val identity: Float,
        val separation: Float,
        val passed: Boolean,
        val failure: String?,
    ) {
        val ran: Boolean get() = failure == null
    }

    /**
     * Paraphrase pairs and unrelated pairs, in the register the app actually
     * handles: sentences from books, not "hello world". Each related pair
     * shares almost no vocabulary with its partner, which is the point — word
     * overlap is what the existing keyword matching already finds, so a pair
     * that overlaps would pass this test without the embedding doing any work.
     */
    val PROBES: List<Probe> = listOf(
        Probe(
            label = "paraphrase",
            first = "Fiction lets strangers cooperate.",
            second = "Shared stories allow people who have never met to work together.",
            related = true,
        ),
        Probe(
            label = "paraphrase",
            first = "Habits form when a behaviour is repeated in a stable context.",
            second = "Doing the same thing in the same setting is what makes it automatic.",
            related = true,
        ),
        Probe(
            label = "unrelated",
            first = "Fiction lets strangers cooperate.",
            second = "The bridge was rebuilt in reinforced concrete after the flood.",
            related = false,
        ),
        Probe(
            label = "unrelated",
            first = "Habits form when a behaviour is repeated in a stable context.",
            second = "Sea ice reflects most of the sunlight that reaches it.",
            related = false,
        ),
    )

    /**
     * The margin by which the worst related pair must beat the best unrelated
     * one.
     *
     * Deliberately a GAP and not a threshold. Absolute cosine values are a
     * property of the model — one export calls everything 0.8, another calls
     * nothing above 0.4 — so a fixed cut-off would either pass a broken chain
     * or fail a working one depending on which model the reader installed.
     * What must hold for any usable embedder is the ordering: things that mean
     * the same must score higher than things that do not, by more than noise.
     */
    const val REQUIRED_SEPARATION = 0.10f

    /**
     * A vector must be almost exactly similar to itself. This catches the
     * degenerate case the rest cannot: a model or a pooling that returns a
     * near-zero vector scores everything about equally, which can accidentally
     * satisfy an ordering test on a small sample.
     */
    const val MIN_IDENTITY = 0.99f

    /**
     * Runs the probes and says plainly whether the chain works.
     *
     * [embed] is the whole pipeline under test — tokenise, run, pool,
     * normalise — so a caller passes `embedder::embed` and this measures what
     * the app will actually do, not a reconstruction of it.
     */
    fun run(embed: (String) -> FloatArray): Report {
        return try {
            val identityVector = embed(PROBES.first().first)
            val identity = EmbeddingMath.cosineSimilarity(identityVector, identityVector)

            val measurements = PROBES.map { probe ->
                Measurement(
                    label = probe.label,
                    similarity = EmbeddingMath.cosineSimilarity(
                        embed(probe.first),
                        embed(probe.second),
                    ),
                    related = probe.related,
                )
            }

            val worstRelated = measurements.filter { it.related }.minOf { it.similarity }
            val bestUnrelated = measurements.filterNot { it.related }.maxOf { it.similarity }
            val separation = worstRelated - bestUnrelated

            Report(
                measurements = measurements,
                identity = identity,
                separation = separation,
                passed = separation >= REQUIRED_SEPARATION && identity >= MIN_IDENTITY,
                failure = null,
            )
        } catch (error: Throwable) {
            // Reported rather than thrown. A model that cannot load at all is
            // an ordinary outcome of pointing this at the wrong file, and the
            // reader needs the message, not a crash.
            Report(
                measurements = emptyList(),
                identity = 0f,
                separation = 0f,
                passed = false,
                failure = error.message ?: error::class.java.simpleName,
            )
        }
    }

    /** The report as lines to show the reader, worst news first. */
    fun summarize(report: Report): List<String> {
        report.failure?.let { return listOf("The embedder could not run: $it") }
        val verdict =
            if (report.passed) {
                "Working — related notes score %.2f above unrelated ones."
                    .format(report.separation)
            } else if (report.identity < MIN_IDENTITY) {
                ("Broken — a text is only %.2f similar to itself, so the vectors are " +
                    "degenerate.").format(report.identity)
            } else {
                ("Broken — related notes beat unrelated ones by only %.2f, which is " +
                    "within noise. Neighbours from this model would be close to random.")
                    .format(report.separation)
            }
        return listOf(verdict) +
            report.measurements.map { "%.2f  %s".format(it.similarity, it.label) }
    }
}
