package com.pagetime.app.data.learning

/**
 * Controls how automatic comprehension analysis chooses between the on-device
 * generators and Gemini.
 *
 * The default is [GEMINI_FIRST]: Gemini produces the cards and concept map, and
 * the on-device generators only fill in when Gemini is unavailable or fails.
 * API volume is bounded by caching: each chapter is processed at most once, so
 * Gemini is contacted per chapter rather than per reading checkpoint.
 */
enum class GenerationMode(
    val key: String,
    val label: String,
    val description: String
) {
    LOCAL_FIRST(
        key = "local_first",
        label = "On-device first",
        description = "Build cards and concepts on the device; Gemini is only used when local results are empty."
    ),
    GEMINI_FIRST(
        key = "gemini_first",
        label = "AI-assisted",
        description = "Prefer Gemini for richer cards and concepts; on-device generation is the fallback."
    );

    companion object {
        fun fromKey(key: String?): GenerationMode =
            entries.firstOrNull { it.key == key } ?: GEMINI_FIRST
    }
}

/**
 * Decides whether a checkpoint should invoke Gemini.
 *
 * In [GenerationMode.LOCAL_FIRST] mode Gemini is skipped entirely whenever the
 * on-device pass produced results; in [GenerationMode.GEMINI_FIRST] mode Gemini
 * is preferred and local generation only fills in when it fails. Either way the
 * call is additionally gated by the per-chapter generation cache, so Gemini is
 * contacted at most once per chapter regardless of checkpoint frequency.
 */
object GenerationPolicy {
    fun shouldCallGemini(
        mode: GenerationMode,
        localItemCount: Int,
        geminiConfigured: Boolean
    ): Boolean = geminiConfigured && (mode == GenerationMode.GEMINI_FIRST || localItemCount == 0)
}
