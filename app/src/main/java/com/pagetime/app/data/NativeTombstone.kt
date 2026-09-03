package com.pagetime.app.data

import android.content.Context
import java.io.File

/**
 * Crash tombstone for the native LLM runtime.
 *
 * A native abort inside MediaPipe (SIGABRT/SIGSEGV) kills the process without
 * ever reaching Kotlin — no try/catch, no uncaught-exception handler, no crash
 * log. The only way to learn WHERE it died is to leave a marker on disk right
 * before each native phase and delete it only when that phase completes.
 *
 * If the app starts and a marker is still present, the process died inside
 * that phase. We record that once into the capture diagnostic log (so the user
 * can copy it) and auto-disable offline inference for the rest of the app's
 * life on this install until the user explicitly clears it — a device that
 * kills itself inside a native phase will keep killing itself, and the app
 * must survive anyway.
 */
object NativeTombstone {

    private const val MARKER_FILE = "native-tombstone.marker"

    /** Phases in the order they occur inside [MediaPipeLlmProvider]. */
    enum class Phase(val label: String) {
        CREATE("creating MediaPipe engine (loadModel)"),
        GENERATE("generating (inference step)"),
    }

    /**
     * True when a previous run died inside a native phase and offline inference
     * was auto-disabled as a result. Checked once per process.
     */
    @Volatile
    var offlineDisabledByTombstone: Boolean = false
        private set

    /** Human-readable summary of the recorded death, for the diagnostic log. */
    @Volatile
    var lastDeathSummary: String? = null
        private set

    /**
     * Called once per process (from the provider's init). If a marker survived
     * from a previous run, the previous run died inside that native phase.
     * Records the death, disables offline inference, and clears the marker so
     * a deliberate re-enable can be observed separately.
     */
    fun checkOnProcessStart(context: Context) {
        val appContext = context.applicationContext
        val marker = markerFile(appContext)
        if (!marker.exists()) return
        val phase = marker.readText().trim().ifEmpty { "unknown phase" }
        lastDeathSummary = "Previous run died during $phase"
        offlineDisabledByTombstone = true
        marker.delete()
        // Record the death into the capture diagnostic log so the user can
        // copy and paste it — this is the evidence that was missing all along.
        runCatching {
            val dir = appContext.getDir("diagnostics", Context.MODE_PRIVATE)
            val log = File(dir, "lumen-capture-diagnostic.log")
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US
            ).format(java.util.Date())
            log.appendText("[$stamp] TOMBSTONE: previous run died during $phase\n")
        }
    }

    /** Writes the "entering phase" marker. Fsyncs to survive an immediate kill. */
    fun enterPhase(context: Context, phase: Phase) {
        try {
            val marker = markerFile(context.applicationContext)
            marker.parentFile?.mkdirs()
            marker.writeText(phase.label)
            // Force the bytes to disk so a native kill cannot erase them.
            java.io.FileOutputStream(marker).use { fos ->
                fos.write(phase.label.toByteArray())
                fos.fd.sync()
            }
        } catch (_: Exception) {
            // Best-effort: a marker failure must never crash the capture.
        }
    }

    /** Deletes the marker after the phase completed successfully. */
    fun exitPhase(context: Context) {
        try {
            markerFile(context.applicationContext).delete()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    private fun markerFile(context: Context): File =
        File(context.getDir("diagnostics", Context.MODE_PRIVATE), MARKER_FILE)

}
