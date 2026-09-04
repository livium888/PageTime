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

    /** Size at which the rolling log is trimmed back to its newest lines. */
    private const val MAX_LOG_BYTES = 200_000

    /**
     * The exact prompt sent and the exact reply received, for the most recent
     * capture only.
     *
     * Kept apart from the rolling log because it is the one thing that answers
     * "did the model actually see the passage" and the one thing that is too
     * big to keep three hundred of. The rolling log records that a capture
     * happened; this records what was said.
     */
    private const val TRANSCRIPT_FILE_NAME = "lumen-last-exchange.log"

    /** Enough of the passage to tell two captures apart, without quoting a page. */
    private const val PASSAGE_HEAD_CHARS = 60

    /**
     * Caps on the verbatim transcript. The prompt cap sits above the largest
     * prompt the app can build, so in practice nothing is cut; it is a guard
     * against a tailored template, not a budget.
     */
    private const val MAX_TRANSCRIPT_PROMPT_CHARS = 12_000
    private const val MAX_TRANSCRIPT_REPLY_CHARS = 4_000

    /**
     * Total and available device RAM in MB, or null when it cannot be read.
     *
     * Logged because it is the number that decides whether a larger model is
     * possible at all. The 1B model in use needs about 900 MB free to load; a
     * 2B-class bundle is several times that, and no amount of prompt work
     * substitutes for a model that will not fit. Guessing at a phone's memory
     * from the other side of a build server is exactly the kind of guess this
     * log exists to replace.
     */
    private fun deviceMemory(context: Context): Pair<Long, Long>? =
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return null
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)) to (info.availMem / (1024 * 1024))
        }.getOrNull()

    /** Writes a short line to app storage right before a live capture attempt. */
    fun logCapture(
        context: Context,
        kind: String,
        modelState: ModelState,
        attemptedGeneration: Boolean,
        bookTitle: String?,
        passageLength: Int,
        passageHead: String? = null,
        promptTokens: Int? = null,
        tokenBudget: Int? = null
    ) {
        val line = buildString {
            append(lineToNow())
            append(" kind=$kind")
            append(" modelState=$modelState")
            append(" attemptedGeneration=$attemptedGeneration")
            deviceMemory(context)?.let { (total, free) ->
                append(" ramTotalMb=$total ramFreeMb=$free")
            }
            if (bookTitle != null) append(" bookTitle=$bookTitle")
            append(" passageLength=$passageLength")
            if (passageHead != null) append(" passageHead=\"$passageHead\"")
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

    /** Clears the diagnostic log and the last verbatim exchange. */
    fun clearLog(context: Context) {
        logFile(context).delete()
        transcriptFile(context).delete()
    }

    fun logFile(context: Context): java.io.File {
        val dir = context.getDir("diagnostics", Context.MODE_PRIVATE)
        return java.io.File(dir, LOG_FILE_NAME)
    }

    private fun transcriptFile(context: Context): java.io.File {
        val dir = context.getDir("diagnostics", Context.MODE_PRIVATE)
        return java.io.File(dir, TRANSCRIPT_FILE_NAME)
    }

    /**
     * Records one prompt/reply exchange verbatim, replacing the previous
     * capture's on the first attempt.
     *
     * Every other field in this file is a measurement ABOUT the exchange —
     * a length, a token count, sixty characters of the passage. None of them
     * can distinguish a model that read the passage and answered badly from a
     * model that never received it, which is the difference that decides what
     * to fix. Only the text itself can, so the text itself is kept.
     *
     * [raw] is null when the call returned nothing at all.
     */
    fun recordExchange(
        context: Context,
        captureKind: String,
        attempt: Int,
        prompt: String,
        raw: String?
    ) {
        try {
            val file = transcriptFile(context)
            val block = buildString {
                append("${lineToNow()} kind=$captureKind attempt=$attempt\n")
                append("--- PROMPT SENT (${prompt.length} chars, ")
                append("~${LlmTokenBudget.estimateTokens(prompt)} tokens) ---\n")
                append(prompt.take(MAX_TRANSCRIPT_PROMPT_CHARS))
                if (prompt.length > MAX_TRANSCRIPT_PROMPT_CHARS) append("\n[...truncated]")
                append("\n--- MODEL REPLY ")
                append(if (raw == null) "(none returned)" else "(${raw.length} chars)")
                append(" ---\n")
                append(raw.orEmpty().take(MAX_TRANSCRIPT_REPLY_CHARS))
                if ((raw?.length ?: 0) > MAX_TRANSCRIPT_REPLY_CHARS) append("\n[...truncated]")
                append("\n")
            }
            // The first attempt starts a fresh transcript; the retry appends to
            // it, so a two-attempt capture is read as one story.
            if (attempt <= 1) file.writeText(block) else file.appendText(block)
        } catch (_: Exception) {
            // Best-effort only; a diagnostic file must not crash the capture.
        }
    }

    /** The last capture's verbatim exchange, or an empty list when there is none. */
    fun lastExchange(context: Context): List<String> {
        val file = transcriptFile(context)
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines() }.getOrDefault(emptyList())
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
            // Always append. This used to WIPE the log the moment it passed
            // 50 KB — so a reader who captured a few times and then went to
            // copy the log found only the lines written since the last reset,
            // and the trim below could never run because the file never got
            // that big. Growth is bounded by trimming to the newest lines,
            // which is what trimLog was there to do all along.
            file.appendText("$line\n")
            if (file.length() > MAX_LOG_BYTES) {
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
            passageLength = passage.length,
            passageHead = passageHead(passage),
        )
    }

    /**
     * The opening of the passage, whitespace-collapsed. passageLength alone
     * cannot answer the question a reader actually asks — "why is every page
     * giving me the same card?" — because the capture window is a fixed radius,
     * so its length barely moves. Two log lines with the same head mean the
     * passage never moved; two different heads mean it did and the model
     * repeated itself. One of those is a position bug and the other is not.
     */
    private fun passageHead(passage: String): String =
        passage.take(PASSAGE_HEAD_CHARS).replace(Regex("\\s+"), " ").trim()

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
            passageHead = passageHead(passage),
            promptTokens = LlmTokenBudget.estimateTokens(prompt),
            tokenBudget = LlmTokenBudget.inputBudget(replyTokens)
        )
    }

    /**
     * What the inference actually cost and whether it landed a card. Duration is
     * the number to tune the passage cap against: more context makes a better
     * card and costs seconds, and neither is knowable without measuring.
     */
    fun recordInference(
        context: Context,
        captureKind: String,
        durationMs: Long,
        attempts: Int,
        usedAi: Boolean,
        rejection: String?,
        backProblem: String? = null,
        repeated: Boolean = false
    ) {
        val line = buildString {
            append(lineToNow())
            append(" kind=$captureKind INFERENCE")
            append(" durationMs=$durationMs")
            append(" attempts=$attempts")
            append(" usedAi=$usedAi")
            if (rejection != null) append(" rejection=$rejection")
            if (backProblem != null) append(" back=$backProblem")
            if (repeated) append(" repeat=SAME_IDEA")
        }
        writeLine(context, line)
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
