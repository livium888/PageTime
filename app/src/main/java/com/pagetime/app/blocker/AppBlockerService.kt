package com.pagetime.app.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.pagetime.app.MainActivity
import com.pagetime.app.PageTimeApp

class AppBlockerService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: TimeUpOverlay? = null
    private var fallbackRedirected = false
    private var fallbackPending = false

    private val controller: BlockController?
        get() = (application as? PageTimeApp)?.container?.blockController

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as? PageTimeApp
        val blockController = app?.container?.blockController
        blockController?.service = this
        app?.container?.usageReconciler?.requestReconcile()

        // A service can reconnect while the blocked app is already on screen and
        // emit no new window-state event. Re-evaluate that window immediately.
        rootInActiveWindow?.packageName?.toString()?.let { packageName ->
            handleForegroundEvent(packageName, rootInActiveWindow?.className?.toString())
        } ?: blockController?.reassertIfBlocked()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val value = event ?: return
        if (value.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            value.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val packageName = value.packageName?.toString() ?: return
        handleForegroundEvent(packageName, value.className?.toString())
    }

    private fun handleForegroundEvent(packageName: String, windowClass: String?) {
        val blockController = controller ?: return
        when (ForegroundEventPolicy.classify(packageName, windowClass, this.packageName)) {
            ForegroundEventPolicy.Action.IGNORE -> Unit
            ForegroundEventPolicy.Action.REASSERT -> blockController.reassertIfBlocked()
            ForegroundEventPolicy.Action.SWITCH -> blockController.onForegroundPackage(packageName)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        dismissTimeUp()
        controller?.service = null
        super.onDestroy()
    }

    /** Attempts to show the block screen; fallback happens on the main looper. */
    fun showTimeUp() {
        if (fallbackPending) return
        fallbackPending = true
        mainHandler.post {
            fallbackPending = false
            if (overlay == null) overlay = TimeUpOverlay(this) { openReader() }
            val attached = overlay?.show() == true
            if (!attached && !fallbackRedirected) {
                fallbackRedirected = true
                openReader()
            }
        }
    }

    fun dismissTimeUp() {
        mainHandler.post {
            overlay?.dismiss()
            fallbackRedirected = false
            fallbackPending = false
        }
    }

    private fun openReader() {
        controller?.releaseBlock()
        performGlobalAction(GLOBAL_ACTION_HOME)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_READER, true)
        }
        startActivity(intent)
    }
}
