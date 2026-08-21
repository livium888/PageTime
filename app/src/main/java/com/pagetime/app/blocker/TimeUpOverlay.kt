package com.pagetime.app.blocker

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.pagetime.app.R

/** A full-screen, input-capturing overlay shown over a blocked app while the balance is zero. */
class TimeUpOverlay(context: Context, onReadNow: () -> Unit) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.view_time_up_overlay, null)
    private var shown = false

    init {
        view.findViewById<View>(R.id.btn_read_now).setOnClickListener { onReadNow() }
    }

    fun show() {
        if (shown) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(view, params)
            shown = true
        } catch (_: Exception) {
            // SYSTEM_ALERT_WINDOW not granted; the user can still return to the reader manually.
        }
    }

    fun dismiss() {
        if (!shown) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
        shown = false
    }
}
