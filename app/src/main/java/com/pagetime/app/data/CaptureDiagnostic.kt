package com.pagetime.app.data

import android.content.Context

/** Small on-device capture diagnostic, shared by the EPUB and YouTube capture paths. */
object CaptureDiagnostic {

    enum class ModelState {
        ready,
        notInstalled,
        damaged,
        notEnoughMemory,
        generating,
        fallbackNoModel
    }

    data class Record(
        val modelState: ModelState,
        val captureKind: String,
        val usedAi: Boolean? = null,
        val reason: String? = null
    ) {
        companion object {
            fun successful(
                modelState: ModelState,
                captureKind: String,
                usedAi: Boolean
            ): Record = Record(
                modelState = modelState,
                captureKind = captureKind,
                usedAi = usedAi
            )

            fun failed(
                modelState: ModelState,
                captureKind: String,
                reason: String
            ): Record = Record(
                modelState = modelState,
                captureKind = captureKind,
                reason = reason
            )
        }

        val displayText: String
            get() = when (modelState) {
                ModelState.ready -> "model ready"
                ModelState.notInstalled -> "model not installed"
                ModelState.damaged -> "model damaged"
                ModelState.notEnoughMemory -> "not enough memory"
                ModelState.generating -> "generating..."
                ModelState.fallbackNoModel -> "fallback (no model)"
            }
    }

    private const val LOG_FILE_NAME = "lumen-capture-diagnostic.log"
    private const val MAX_LINES = 300

    /** Writes a short line to app storage right before a live capture attempt. */
    fun logCapture(
        context: Context,
        kind: String,
        modelState: ModelState,
        attemptedGeneration: Boolean,
        bookTitle: String?,
        passageLength: Int,
        promptTokens: Int? = null,
        tokenBudget: Int? = null
    ) {
        val line = buildString {
            append(lineToNow())
            append(" kind=$kind")
            append(" modelState=$modelState")
            append(" attemptedGeneration=$attemptedGeneration")
            if (bookTitle != null) append(" bookTitle=$bookTitle")
            append(" passageLength=$passageLength")
            if (promptTokens != null && tokenBudget != null) {
                append(" promptTokens=$promptTokens/$tokenBudget")
                if (promptTokens > tokenBudget) append(" OVER_BUDGET")
            }
        }
        writeLine(context, line)
    }

    /** Returns the most recent capture log lines, newest first, up to [MAX_LINES]. */
    fun recentLog(context: Context): List<String> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(MAX_LINES).reversed()
    }

    /** Clears the diagnostic log. */
    fun clearLog(context: Context) {
        logFile(context).delete()
    }

    fun logFile(context: Context): java.io.File {
        val dir = context.getDir("diagnostics", Context.MODE_PRIVATE)
        return java.io.File(dir, LOG_FILE_NAME)
    }

    private fun lineToNow(): String {
        val now = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            java.util.Locale.US
        ).format(java.util.Date())
        return "[${now}]"
    }

    private fun writeLine(context: Context, line: String) {
        try {
            val file = logFile(context)
            if (file.length() > 0 && file.length() < 50_000) {
                file.appendText("$line\n")
            } else {
                file.writeText("$line\n")
            }
            if (file.length() > 500_000) {
                trimLog(file)
            }
        } catch (_: Exception) {
            // Best-effort only; a diagnostic file must not crash the capture.
        }
    }

    private fun trimLog(file: java.io.File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    fun evaluate(local: LlmProvider, modelStore: LumenModelStore): ModelState {
        if (!modelStore.isInstalled()) return ModelState.notInstalled
        if (!modelStore.isModelFileIntact()) return ModelState.damaged
        if (!local.isAvailable) return ModelState.fallbackNoModel
        if (!local.hasEnoughMemory()) return ModelState.notEnoughMemory
        return ModelState.ready
    }

    /**
     * [passage] is the whole captured passage, never a preview. Its length is
     * what decides whether the prompt fits the model's token budget, and
     * logging a truncated preview's length instead reported a constant 120 on
     * every capture — hiding the one value that mattered while the offline
     * model was aborting the process on an over-budget prompt.
     */
    fun recordPreCapture(
        context: Context,
        modelState: ModelState,
        captureKind: String,
        passage: String
    ) {
        logCapture(
            context = context,
            kind = captureKind,
            modelState = modelState,
            attemptedGeneration = modelState == ModelState.ready,
            bookTitle = null,
            passageLength = passage.length
        )
    }

    /**
     * Logged with the prompt actually handed to the model, so the token count
     * is the real one rather than a reconstruction that can drift from it.
     */
    fun recordGenerating(
        context: Context,
        captureKind: String,
        passage: String,
        prompt: String,
        replyTokens: Int
    ) {
        logCapture(
            context = context,
            kind = captureKind,
            modelState = ModelState.generating,
            attemptedGeneration = true,
            bookTitle = null,
            passageLength = passage.length,
            promptTokens = LlmTokenBudget.estimateTokens(prompt),
            tokenBudget = LlmTokenBudget.inputBudget(replyTokens)
        )
    }

    fun recordFailure(context: Context, captureKind: String, reason: String) {
        val file = logFile(context)
        if (file.exists()) {
            try {
                file.appendText("${lineToNow()} FAILURE reason=$reason\n")
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun recordAfterCapture(context: Context, modelState: ModelState, captureKind: String, usedAi: Boolean) {
        logCapture(
            context = context,
            kind = captureKind,
            modelState = modelState,
            attemptedGeneration = usedAi,
            bookTitle = null,
            passageLength = 0
        )
    }
}
