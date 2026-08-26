package com.pagetime.app.data.local

import com.pagetime.app.data.learning.GenerationMode

/**
 * Controls how often automatic comprehension analysis is attempted while reading.
 * The default is deliberately light so PageTime remains a reading app.
 */
enum class AiAnalysisLevel(
    val key: String,
    val label: String,
    val description: String,
    val intervalSeconds: Long
) {
    LIGHT(
        key = "light",
        label = "Light",
        description = "Checks every ~12 min — only the first per chapter uses Gemini",
        intervalSeconds = 12 * 60L
    ),
    BALANCED(
        key = "balanced",
        label = "Balanced",
        description = "Checks every ~6 min — only the first per chapter uses Gemini",
        intervalSeconds = 6 * 60L
    ),
    FREQUENT(
        key = "frequent",
        label = "Frequent",
        description = "Checks every ~3 min — only the first per chapter uses Gemini",
        intervalSeconds = 3 * 60L
    ),
    INTENSIVE(
        key = "intensive",
        label = "Intensive",
        description = "Checks every ~90 s — only the first per chapter uses Gemini",
        intervalSeconds = 90L
    );

    companion object {
        fun fromKey(key: String?): AiAnalysisLevel =
            entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

data class AiSettings(
    val analysisLevel: AiAnalysisLevel = AiAnalysisLevel.LIGHT,
    val generationMode: GenerationMode = GenerationMode.GEMINI_FIRST
)
