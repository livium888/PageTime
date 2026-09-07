package com.pagetime.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.pagetime.app.data.AppContainer
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.review.ReviewReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PageTimeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /**
     * For the little startup work that outlives no screen.
     *
     * SupervisorJob so one failure here cannot cancel the rest; it lives as
     * long as the process, which is the point.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Every uncaught exception (Kotlin or native, when the runtime routes it
        // here) is written to filesDir/crash.log before the process dies. The next
        // support report can read that file to see the EXACT stack trace instead
        // of guessing at the cause.
        installCrashLogger()
        container = AppContainer(this)
        // Re-armed at every launch: WorkManager keeps periodic work across
        // reboots, but a reader who reinstalls or clears data would otherwise
        // have the setting still on and nothing scheduled behind it. KEEP
        // means an existing schedule is left alone rather than restarted.
        scheduleReviewReminders()
    }

    private fun scheduleReviewReminders() {
        appScope.launch {
            runCatching {
                if (container.settingsRepository.reviewReminders()) {
                    ReviewReminderWorker.schedule(this@PageTimeApp)
                }
            }
        }
    }

    /** Cover images go through the same resilient HTTP client as the catalog. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { AppHttp.newClient(callTimeoutSeconds = 60L) }
            .crossfade(true)
            .build()

    private fun installCrashLogger() {
        val crashDir = File(filesDir, "crash")
        crashDir.mkdirs()
        // Kept so the platform still gets to handle the crash. Replacing it
        // outright is what made every crash look like the app quietly closing.
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(crashDir, "crash-$timestamp.log")
                val stack = StringWriter().apply {
                    throwable.printStackTrace(PrintWriter(this))
                }
                file.writeText(
                    "Thread: ${thread.name}\n" +
                        "Time: ${Date()}\n" +
                        "Exception: ${throwable}\n" +
                        stack.toString(),
                )
                Log.e("PageTimeCrash", "Uncaught exception on ${thread.name}", throwable)
            } catch (_: Throwable) {
                // The crash logger must never make things worse.
            } finally {
                // Hand the crash back to the platform handler, which is what
                // shows "PageTime keeps stopping", files the report, and lets
                // the system restart the app. Killing the process here instead
                // did none of that: the app vanished to the home screen with no
                // dialog and no report, which reads as a native death and hid
                // every ordinary Kotlin crash behind that appearance. The kill
                // stays only as the fallback for when there is no platform
                // handler to hand to — the process must not survive.
                platform?.uncaughtException(thread, throwable)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    companion object {
        /** Directory the crash logger writes to; support can read it via adb. */
        fun crashDirOf(context: android.content.Context): File =
            File(context.filesDir, "crash")
    }
}
