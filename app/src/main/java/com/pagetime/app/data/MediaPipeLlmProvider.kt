package com.pagetime.app.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
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
 *
 * Memory preflight: loading the model needs ~800 MB of contiguous native memory.
 * If the device cannot provide that, we skip the native call entirely and let
 * the caller fall back to the safe non-AI draft. This prevents OOM-driven
 * process kills that Kotlin cannot catch.
 */
class MediaPipeLlmProvider(
    private val context: Context,
    private val modelStore: LumenModelStore,
) : LlmProvider {
    override val kind: LlmProviderKind = LlmProviderKind.OFFLINE

    override val isAvailable: Boolean
        get() = modelStore.isInstalled() && modelStore.modelFile.length() > 0

    private val inferenceMutex = Mutex()

    /** Set after the first native crash/OOM — disables offline inference for the rest of the session. */
    @Volatile
    private var nativeFailed = false

    override suspend fun generate(request: LlmRequest): Result<LlmResult> {
        if (nativeFailed) {
            return Result.failure(
                IllegalStateException("Offline model disabled after a previous failure")
            )
        }
        return try {
            Result.success(infer(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Offline model failed", error)
            nativeFailed = true
            Result.failure(error)
        }
    }

    /** Loads the runtime, runs one request, and always releases the weights. */
    private suspend fun infer(request: LlmRequest): LlmResult =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                require(isAvailable) {
                    "No offline model is installed. Download it in Settings first."
                }
                require(hasEnoughNativeMemory()) {
                    "Not enough memory to load the offline model. Close other apps and try again."
                }
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelStore.modelFile.absolutePath)
                    .setMaxTokens(request.maxOutputTokens.coerceIn(128, 512))
                    .setMaxTopK(40)
                    .build()
                val llm = LlmInference.createFromOptions(context, options)
                try {
                    val prompt = buildString {
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

    /**
     * Quick check: does the device have enough free memory for the ~521 MB model
     * plus runtime overhead? We need roughly 800 MB of headroom. If not, skip
     * the native call so the process survives.
     */
    private fun hasEnoughNativeMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeMb = memInfo.availMem / (1024 * 1024)
        Log.d(TAG, "Available native memory: ${freeMb}MB")
        return freeMb > MIN_MEMORY_MB
    }

    companion object {
        private const val TAG = "MediaPipeLlm"
        private const val MIN_MEMORY_MB = 800L
    }
}
