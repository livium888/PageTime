package com.pagetime.app.data

/** Provider selected for optional AI-assisted learning features. */
enum class LlmProviderKind(
    val key: String,
    val label: String,
    val description: String,
) {
    OFFLINE(
        key = "offline",
        label = "Offline model",
        description = "Runs on this device when a compatible model is installed.",
    ),
    GEMINI(
        key = "gemini",
        label = "Gemini",
        description = "Uses the configured Google Gemini API key.",
    ),
    ASK_EVERY_TIME(
        key = "ask",
        label = "Ask every time",
        description = "Prefers Gemini when a key is configured, otherwise uses the local model.",
    );

    companion object {
        fun fromKey(key: String?): LlmProviderKind = entries.firstOrNull { it.key == key } ?: GEMINI
    }
}

data class LlmRequest(
    val prompt: String,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int = 512,
)

data class LlmResult(
    val text: String,
    val provider: LlmProviderKind,
)

interface LlmProvider {
    val kind: LlmProviderKind
    val isAvailable: Boolean

    suspend fun generate(request: LlmRequest): Result<LlmResult>
}

/**
 * Explicit placeholder until the optional native runtime and model weights are
 * installed. It fails clearly rather than silently sending offline requests to
 * Gemini or pretending that a model is bundled in the APK.
 */
class OfflineLlmProvider : LlmProvider {
    override val kind: LlmProviderKind = LlmProviderKind.OFFLINE
    override val isAvailable: Boolean = false

    override suspend fun generate(request: LlmRequest): Result<LlmResult> =
        Result.failure(
            IllegalStateException(
                "No offline model is installed. Download a compatible model in AI settings first.",
            ),
        )
}

/** Where a "capture a card from a selection" request should run. */
enum class LumenDraftSource { GEMINI, LOCAL, FALLBACK }

/**
 * Decides the capture source from the selected provider and what is actually
 * configured on the device. Pure and unit-tested.
 *
 * ASK_EVERY_TIME currently prefers Gemini (matching today's behavior) and only
 * falls back to the local model when Gemini isn't configured; a per-request
 * chooser dialog can be layered on top of this later.
 */
object LumenDraftRouter {
    fun sourceFor(
        provider: LlmProviderKind,
        geminiConfigured: Boolean,
        localModelAvailable: Boolean,
    ): LumenDraftSource =
        when (provider) {
            LlmProviderKind.OFFLINE ->
                if (localModelAvailable) LumenDraftSource.LOCAL else LumenDraftSource.FALLBACK
            LlmProviderKind.GEMINI ->
                if (geminiConfigured) LumenDraftSource.GEMINI else LumenDraftSource.FALLBACK
            LlmProviderKind.ASK_EVERY_TIME ->
                when {
                    geminiConfigured -> LumenDraftSource.GEMINI
                    localModelAvailable -> LumenDraftSource.LOCAL
                    else -> LumenDraftSource.FALLBACK
                }
        }
}
