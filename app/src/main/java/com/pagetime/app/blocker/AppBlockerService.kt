package com.pagetime.app.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityEvent
import com.pagetime.app.MainActivity
import com.pagetime.app.PageTimeApp

class AppBlockerService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foregroundRefresh = object : Runnable {
        override fun run() {
            rootInActiveWindow?.packageName?.toString()?.let { controller?.onForegroundPackage(it) }
            mainHandler.postDelayed(this, FOREGROUND_REFRESH_MS)
        }
    }
    private var overlay: TimeUpOverlay? = null

    private val controller: BlockController?
        get() = (application as? PageTimeApp)?.container?.blockController

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as? PageTimeApp
        app?.container?.blockController?.service = this
        app?.container?.usageReconciler?.requestReconcile()
        // The service can connect (or reconnect after a process death) while a
        // blocked app is already in front. Without this, nothing is enforced until
        // that app happens to emit another window event — which it never does while
        // it stays foreground.
        rootInActiveWindow?.packageName?.toString()?.let { controller?.onForegroundPackage(it) }
        mainHandler.removeCallbacks(foregroundRefresh)
        mainHandler.post(foregroundRefresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val eventPackage = event.packageName?.toString()
                if (!ForegroundEventPolicy.isForegroundChange(
                        packageName = eventPackage,
                        className = event.className?.toString(),
                        selfPackage = packageName
                    )
                ) {
                    // Transient chrome (shade, keyboard, toast) or our own overlay:
                    // the blocked app is still there, so just make sure we still are.
                    controller?.reassert()
                    return
                }
                controller?.onForegroundPackage(eventPackage)
            }
            // Something re-stacked the windows — possibly on top of our block screen.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> controller?.reassert()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(foregroundRefresh)
        dismissTimeUp()
        controller?.service = null
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_REFRESH_MS = 2_000L
    }

    /** Shows the block screen. Returns false if no overlay window could be added. */
    fun showTimeUp(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return showTimeUpNow()
        val result = BooleanArray(1)
        val latch = CountDownLatch(1)
        mainHandler.post {
            result[0] = showTimeUpNow()
            latch.countDown()
        }
        // BlockController calls this from its background scope. Wait briefly for
        // the actual main-thread window operation so a not-yet-created overlay is
        // not mistaken for a permission failure.
        return try {
            latch.await(500, TimeUnit.MILLISECONDS)
            result[0]
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun showTimeUpNow(): Boolean {
        val current = overlay ?: TimeUpOverlay(this) { openReader() }.also { overlay = it }
        return current.show()
    }

    fun dismissTimeUp() {
        mainHandler.post { overlay?.dismiss() }
    }

    /**
     * Last-resort enforcement when no overlay can be drawn: an accessibility service
     * can always send the user home, no special permission required.
     */
    fun bounceOut() {
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_HOME)
            openReader()
        }
    }

    private fun openReader() {
        controller?.releaseBlock()
        dismissTimeUp()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_READER, true)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Background activity launch refused; the user is at least out of the app.
        }
    }
}
