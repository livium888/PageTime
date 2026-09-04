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
            // The poll sees whatever window holds input focus right now — which
            // can be our own overlay, the keyboard, the shade, or a secure window
            // we cannot inspect (null). Only a trusted package (a real, foreign
            // app window) may drive foreground decisions; anything else is
            // "unknown" and must never start or extend a block. Without this
            // filter the block screen appeared over the home screen and over
            // apps that were merely open in the background.
            val pkg = rootInActiveWindow?.packageName?.toString()
            controller?.onPolledForeground(pkg)
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
        // it stays foreground. Same trust filter as the poll: a null or transient
        // focused window must not start a block on a guess.
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (ForegroundEventPolicy.isTrustedForegroundPackage(pkg, packageName)) {
            controller?.onForegroundPackage(pkg)
        }
        mainHandler.removeCallbacks(foregroundRefresh)
        mainHandler.post(foregroundRefresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The event's package names the window that CHANGED, not the app
                // the reader is looking at. Backgrounded apps emit these
                // constantly, and a blocked app being sent behind the launcher
                // emits one on its way out — so believing the event re-asserted
                // the block over the home screen, and did it again on every
                // event the app fired from the background. That is the block
                // screen that kept coming back after pressing Home.
                //
                // The event is now only a reason to look, never the answer.
                // What decides is the window actually in front — the same
                // signal the poll uses, so there is one authority instead of
                // two that can disagree.
                val inFront = ForegroundEventPolicy.foregroundForEvent(
                    activeWindowPackage = rootInActiveWindow?.packageName?.toString(),
                    selfPackage = packageName
                )
                if (inFront != null) controller?.onForegroundPackage(inFront)
                // Untrusted means our own overlay, transient chrome, or a window
                // that cannot be inspected. Unknown changes nothing: the
                // enforcement loop keeps a real block attached, and its own
                // staleness check ends one that nothing confirms any more.
            }
            // Something re-stacked the windows — possibly on top of our block screen.
            // Re-assert only when the focused window still names the blocked app;
            // a re-stack while we are NOT in the blocked app must never draw the
            // block screen (the reported "overlay when not in the blocked app" bug).
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = rootInActiveWindow?.packageName?.toString()
                if (ForegroundEventPolicy.isTrustedForegroundPackage(pkg, packageName)) {
                    controller?.reassert()
                }
            }
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

    /**
     * The package of the window that currently holds input focus, or null when
     * that window cannot be inspected (secure windows, some system surfaces).
     * Used by the enforce loop to confirm the blocked app is still in front
     * before drawing the overlay — null is "unknown", never "gone".
     */
    fun focusedWindowPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Whether the block overlay is currently attached. */
    fun isTimeUpShowing(): Boolean = overlay?.isShowing() == true

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
