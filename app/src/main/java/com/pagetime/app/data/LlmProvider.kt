package com.pagetime.app.data

import kotlin.math.ceil

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

/**
 * Token budget for the on-device runtime.
 *
 * MediaPipe spends a single budget on input and output together, and when the
 * input alone overflows it the native runtime aborts the process — a SIGABRT
 * that no Kotlin catch, and no uncaught-exception handler, ever sees. Every
 * prompt is therefore measured against this budget before it reaches native
 * code. Android-free so the invariant can be unit-tested.
 */
object LlmTokenBudget {
    /**
     * Total tokens the engine is built with: input + output share it.
     *
     * 1,536 was the size that first stopped the crash, chosen to be obviously
     * safe rather than to be right. It bought the passage about 2,400
     * characters, which is roughly two phone pages — enough for a claim, and
     * thin for the reason behind it. 2,048 buys another 1,200 characters of
     * book at the cost of a slightly larger KV cache and a second or two of
     * inference, and a load that cannot be allocated still fails into the
     * plain draft rather than the crash, because the budget is measured before
     * native code is reached either way.
     */
    const val MAX_TOKENS = 2_048

    /** English runs ~4 chars/token; 3.5 keeps the estimate conservative. */
    private const val CHARS_PER_TOKEN = 3.5

    /**
     * Cheap upper bound on the tokens [text] occupies. Deliberately
     * pessimistic: over-estimating costs one fallback draft, while
     * under-estimating costs the whole process.
     */
    fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()

    /** Tokens left for the prompt once [maxOutputTokens] are reserved for the reply. */
    fun inputBudget(maxOutputTokens: Int): Int = MAX_TOKENS - maxOutputTokens

    fun fits(prompt: String, maxOutputTokens: Int): Boolean =
        estimateTokens(prompt) <= inputBudget(maxOutputTokens)
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

    /** MediaPipe-backed providers only. Returns true when the device reports
     * enough free native memory to safely load the model. Never called by a
     * provider whose inference does not use native memory. */
    fun hasEnoughMemory(): Boolean
}

/**
 * Explicit placeholder until the optional native runtime and model weights are
 * installed. It fails clearly rather than silently sending offline requests to
 * Gemini or pretending that a model is bundled in the APK.
 */
class OfflineLlmProvider : LlmProvider {
    override val kind: LlmProviderKind = LlmProviderKind.OFFLINE
    override val isAvailable: Boolean = false

    override fun hasEnoughMemory(): Boolean = false

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
