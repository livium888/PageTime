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

    private val controller: BlockController?
        get() = (application as? PageTimeApp)?.container?.blockController

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as? PageTimeApp
        app?.container?.blockController?.service = this
        app?.container?.usageReconciler?.requestReconcile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        controller?.onForegroundPackage(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        dismissTimeUp()
        controller?.service = null
        super.onDestroy()
    }

    fun showTimeUp() {
        mainHandler.post {
            if (overlay == null) overlay = TimeUpOverlay(this) { openReader() }
            overlay?.show()
        }
    }

    fun dismissTimeUp() {
        mainHandler.post { overlay?.dismiss() }
    }

    private fun openReader() {
        dismissTimeUp()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_READER, true)
        }
        startActivity(intent)
    }
}
