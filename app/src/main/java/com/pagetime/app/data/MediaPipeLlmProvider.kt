package com.pagetime.app.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

/**
 * On-device LLM inference via MediaPipe's tasks-genai runtime, powered by the
 * weights managed by [LumenModelStore].
 *
 * The model is loaded once and kept resident for the app session, then
 * released when the model is replaced/deleted or the OS reports memory
 * pressure. The previous behavior closed the native model after every
 * request: the first load succeeded, the freed ~600 MB block got
 * fragmented by continued app activity, and the SECOND load aborted the
 * process inside MediaPipe (uncatchable SIGABRT, no Java crash log) —
 * exactly the "first capture works, every capture after crashes" report.
 * Holding the model resident removes the repeated load cycle entirely.
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
        get() = modelStore.isInstalled() && modelStore.isModelFileIntact()

    private val inferenceMutex = Mutex()
    private val modelMutex = Mutex()

    /** Loaded model instance, kept alive for the session. Null when not loaded. */
    @Volatile
    private var cachedModel: LlmInference? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set after the first native crash/OOM — disables offline inference for the rest of the session. */
    @Volatile
    var nativeFailed = false
        private set

    /**
     * Tracks the model file's size at the last load, so a re-download that
     * replaces the file at the same path is detected and the stale native
     * handle is discarded before the next capture.
     */
    private var loadedModelSize: Long = -1

    private val modelFileSize: Long
        get() = modelStore.modelFile.length()

    init {
        // Detect a native death from a previous process: a surviving tombstone
        // marker means the last run died inside a native phase. This records
        // the phase into the capture diagnostic log and auto-disables offline
        // inference so the app survives instead of crash-looping.
        runCatching { NativeTombstone.checkOnProcessStart(context) }
        if (NativeTombstone.offlineDisabledByTombstone) {
            nativeFailed = true
            Log.e(TAG, "Offline inference disabled: ${NativeTombstone.lastDeathSummary}")
        }
        // Observe the store's status so a model replacement (re-download) or
        // corruption is detected even when no capture is in flight. The store
        // emits on a background dispatcher; reset() is lightweight and safe to
        // call from any context.
        scope.launch {
            modelStore.status.collect { status ->
                when (status) {
                    is LumenModelStatus.Ready -> {
                        if (status.bytes != loadedModelSize) reset()
                    }
                    is LumenModelStatus.Downloading,
                        is LumenModelStatus.UpdateAvailable -> Unit
                    is LumenModelStatus.NotDownloaded,
                        is LumenModelStatus.Failed -> reset()
                }
            }
        }
        // Release the resident model when the OS is genuinely running low, so
        // holding it never starves the rest of the system. The next capture
        // reloads lazily — one load, not a load/close cycle per request.
        runCatching {
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    override fun onLowMemory() = dropForMemoryPressure()
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                            dropForMemoryPressure()
                        }
                    }
                },
            )
        }
    }

    private fun dropForMemoryPressure() {
        scope.launch { dropCachedModel("memory pressure") }
    }

    override fun hasEnoughMemory(): Boolean = hasEnoughNativeMemory()

    override suspend fun generate(request: LlmRequest): Result<LlmResult> {
        if (nativeFailed) {
            return Result.failure(
                IllegalStateException(
                    NativeTombstone.lastDeathSummary
                        ?: "Offline model disabled after a previous failure"
                )
            )
        }
        return try {
            Result.success(infer(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Offline model failed", error)
            nativeFailed = true
            scope.launch { dropCachedModel("failure") }
            Result.failure(error)
        }
    }

    /**
     * Runs the request against the resident model, loading it first if it was
     * dropped (memory pressure, re-download). The model stays loaded after the
     * request returns — see the class doc for why the per-request close was
     * removed.
     */
    private suspend fun infer(request: LlmRequest): LlmResult =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                require(isAvailable) {
                    "No offline model is installed. Download it in Settings first."
                }
                // Belt and braces: re-check right before native use. A corrupt
                // file aborts the process inside MediaPipe with no exception
                // Kotlin can catch — refuse the native call instead.
                require(modelStore.isModelFileIntact()) {
                    "The offline model file is damaged. Delete and re-download it in Settings."
                }
                if (hasEnoughNativeMemory() == false && cachedModel == null) {
                    // Only refuse when we would have to do a fresh load; if the
                    // model is already resident, running it costs no new memory.
                    throw IllegalStateException(
                        "Not enough memory to load the offline model. Close other apps and try again."
                    )
                }

                val model = acquireModel()
                val prompt = buildString {
                    request.systemInstruction?.let { append(it).append("\n\n") }
                    append(request.prompt)
                }
                Log.d(
                    TAG,
                    "Generating (model=${modelStore.modelFile.name}, " +
                        "remaining=${freeNativeMemoryMb()}MB)"
                )
                NativeTombstone.enterPhase(context, NativeTombstone.Phase.GENERATE)
                val text = model.generateResponse(prompt).trim()
                NativeTombstone.exitPhase(context)
                check(text.isNotBlank()) { "The offline model returned an empty response" }
                LlmResult(text, LlmProviderKind.OFFLINE)
            }
        }

    /**
     * Returns the resident model, loading it on first use (or after a drop).
     */
    private suspend fun acquireModel(): LlmInference =
        modelMutex.withLock {
            // Re-check availability under the lock after the caller's preflight.
            if (cachedModel != null &&
                modelStore.isModelFileIntact() &&
                modelStore.modelFile.length() == loadedModelSize
            ) {
                Log.d(TAG, "Reuse resident model")
                return cachedModel!!
            }
            // Discard any stale handle whose backing file was replaced or
            // re-downloaded.
            cachedModel?.close()
            cachedModel = null
            loadedModelSize = -1

            Log.d(TAG, "Loading model from ${modelStore.modelFile.absolutePath} " +
                "(available=${freeNativeMemoryMb()}MB)")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelStore.modelFile.absolutePath)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            // Tombstone around the native load: if the process dies inside
            // createFromOptions, the marker survives and the next launch knows
            // exactly which phase killed it, then auto-disables offline AI so
            // the app keeps working via the non-AI fallback.
            NativeTombstone.enterPhase(context, NativeTombstone.Phase.CREATE)
            val model = LlmInference.createFromOptions(context, options)
            NativeTombstone.exitPhase(context)
            cachedModel = model
            loadedModelSize = modelStore.modelFile.length()
            Log.d(TAG, "Model loaded and kept resident (size=${loadedModelSize}B)")
            model
        }

    /**
     * Closes and forgets the resident model without clearing the failure flag.
     * Used when the OS is under memory pressure; the next capture reloads.
     */
    private suspend fun dropCachedModel(reason: String) {
        modelMutex.withLock {
            if (cachedModel == null) return
            cachedModel?.close()
            cachedModel = null
            loadedModelSize = -1
            Log.d(TAG, "Resident model dropped ($reason)")
        }
    }

    /**
     * Closes the cached model and clears the failure flag so the next capture
     * can retry. Called from the settings screen when the user re-downloads the
     * model, and from [generate] when a native failure is detected.
     */
    private suspend fun reset() {
        dropCachedModel("model store change")
        nativeFailed = false
        Log.d(TAG, "MediaPipeLlmProvider reset")
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

    private fun freeNativeMemoryMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    companion object {
        private const val TAG = "MediaPipeLlm"
        private const val MIN_MEMORY_MB = 800L
    }
}
