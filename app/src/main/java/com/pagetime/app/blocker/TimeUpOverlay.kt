package com.pagetime.app.blocker

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.pagetime.app.R

/**
 * A full-screen, input-capturing overlay shown over a blocked app while the balance is zero.
 *
 * Window type matters here. `TYPE_APPLICATION_OVERLAY` needs the SYSTEM_ALERT_WINDOW
 * special permission, so if the user never granted "Display over other apps" the
 * `addView` simply threw and the block screen silently never appeared. An
 * AccessibilityService can instead use `TYPE_ACCESSIBILITY_OVERLAY`, which needs no
 * such grant and is laid out above ordinary overlays — so that is the primary path,
 * with the old type kept as a fallback.
 */
class TimeUpOverlay(context: Context, onReadNow: () -> Unit) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.view_time_up_overlay, null)

    init {
        view.findViewById<View>(R.id.btn_read_now).setOnClickListener { onReadNow() }
        // Focusable so the overlay swallows BACK instead of letting it fall through
        // to the blocked app underneath.
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
    }

    /** True while our window is actually attached — not merely "we asked for it". */
    fun isShowing(): Boolean = view.parent != null

    /**
     * Idempotent. Returns whether the overlay is up, so the caller can fall back to
     * bouncing the user out of the app when no window could be added at all.
     */
    fun show(): Boolean {
        if (isShowing()) return true
        for (type in WINDOW_TYPES) {
            try {
                windowManager.addView(view, params(type))
                view.requestFocus()
                return true
            } catch (_: Exception) {
                // Type unavailable (e.g. SYSTEM_ALERT_WINDOW not granted); try the next.
            }
        }
        return false
    }

    fun dismiss() {
        if (!isShowing()) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
    }

    private fun params(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        type,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply { gravity = Gravity.CENTER }

    private companion object {
        val WINDOW_TYPES = intArrayOf(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )
    }
}
