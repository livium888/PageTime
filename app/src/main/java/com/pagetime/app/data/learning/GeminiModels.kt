package com.pagetime.app.data.learning

data class GeminiModel(
    val name: String,
    val displayName: String,
    val description: String,
    val supportedGenerationMethods: List<String>
) {
    val id: String get() = name.removePrefix("models/")
}

data class GeminiConnectionResult(
    val models: List<GeminiModel>,
    val selectedModel: String
)
