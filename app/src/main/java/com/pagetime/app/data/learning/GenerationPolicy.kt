package com.pagetime.app.data.learning

/**
 * Controls how automatic comprehension analysis chooses between the on-device
 * generators and Gemini.
 *
 * The default is [LOCAL_FIRST]: cards and the concept map are built on the device
 * and Gemini is only contacted when the local pass produced nothing usable, which
 * keeps API usage near zero for most reading sessions.
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
            entries.firstOrNull { it.key == key } ?: LOCAL_FIRST
    }
}

/**
 * Decides whether a checkpoint should invoke Gemini.
 *
 * In [GenerationMode.LOCAL_FIRST] mode Gemini is skipped entirely whenever the
 * on-device pass produced results; in [GenerationMode.GEMINI_FIRST] mode Gemini
 * is preferred and local generation only fills in when it fails.
 */
object GenerationPolicy {
    fun shouldCallGemini(
        mode: GenerationMode,
        localItemCount: Int,
        geminiConfigured: Boolean
    ): Boolean = geminiConfigured && (mode == GenerationMode.GEMINI_FIRST || localItemCount == 0)
}
