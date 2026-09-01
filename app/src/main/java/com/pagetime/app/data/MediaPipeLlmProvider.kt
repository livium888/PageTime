package com.pagetime.app.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM inference via MediaPipe's tasks-genai runtime, powered by the
 * weights managed by [LumenModelStore].
 *
 * The runtime is created per request and closed afterwards, so the ~500 MB
 * weights are never pinned in RAM when the user isn't generating — a deliberate
 * trade-off (a few seconds of load time) against silently holding the model in
 * memory for the whole session.
 */
class MediaPipeLlmProvider(
    private val context: Context,
    private val modelStore: LumenModelStore,
) : LlmProvider {
    override val kind: LlmProviderKind = LlmProviderKind.OFFLINE

    override val isAvailable: Boolean
        get() = modelStore.isInstalled()

    override suspend fun generate(request: LlmRequest): Result<LlmResult> =
        try {
            Result.success(infer(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    /** Loads the runtime, runs one request, and always releases the weights. */
    private suspend fun infer(request: LlmRequest): LlmResult =
        withContext(Dispatchers.Default) {
            require(isAvailable) {
                "No offline model is installed. Download it in Settings first."
            }
            val options =
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelStore.modelFile.absolutePath)
                    .setMaxTokens(request.maxOutputTokens.coerceIn(128, 1024))
                    .build()
            val llm = LlmInference.createFromOptions(context, options)
            try {
                val prompt =
                    buildString {
                        request.systemInstruction?.let { append(it).append("\n\n") }
                        append(request.prompt)
                    }
                val text = llm.generateResponse(prompt).trim()
                check(text.isNotBlank()) { "The offline model returned an empty response" }
                LlmResult(text, LlmProviderKind.OFFLINE)
            } finally {
                llm.close()
            }
        }
}
