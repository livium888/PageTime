package com.pagetime.app.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        get() = modelStore.isInstalled() && modelStore.modelFile.length() > 0

    /** Native MediaPipe cannot safely load two copies of the model concurrently. */
    private val inferenceMutex = Mutex()

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
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
            require(isAvailable) {
                "No offline model is installed. Download it in Settings first."
            }
            val options =
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelStore.modelFile.absolutePath)
                    .setMaxTokens(request.maxOutputTokens.coerceIn(128, 1024))
                    // Keep sampling narrow so a small model reliably follows the
                    // requested output format instead of wandering into prose.
                    .setMaxTopK(40)
                    .build()
            val llm = try {
                LlmInference.createFromOptions(context, options)
            } catch (error: Throwable) {
                throw IllegalStateException("Offline model could not be loaded on this device", error)
            }
            try {
                val prompt =
                    buildString {
                        request.systemInstruction?.let { append(it).append("\n\n") }
                        append(request.prompt)
                    }
                val text = try {
                    llm.generateResponse(prompt).trim()
                } catch (error: Throwable) {
                    throw IllegalStateException("Offline model inference failed", error)
                }
                check(text.isNotBlank()) { "The offline model returned an empty response" }
                LlmResult(text, LlmProviderKind.OFFLINE)
            } finally {
                llm.close()
            }
            }
        }
}
