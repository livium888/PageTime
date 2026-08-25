package com.pagetime.app.blocker

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.pagetime.app.R

/** A full-screen, input-capturing overlay shown over a blocked app while the balance is zero. */
class TimeUpOverlay(context: Context, onReadNow: () -> Unit) {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(appContext).inflate(R.layout.view_time_up_overlay, null)

    init {
        view.findViewById<View>(R.id.btn_read_now).setOnClickListener { onReadNow() }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }
    }

    /** Returns true only when the view is actually attached to a window. */
    fun show(): Boolean {
        if (view.parent != null) return true

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (tryAdd(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags)) return true

        // This path is useful when the service is running on a vendor Android build
        // that rejects accessibility overlays. It still requires the user grant.
        if (Settings.canDrawOverlays(appContext) &&
            tryAdd(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags)
        ) {
            return true
        }
        return false
    }

    fun dismiss() {
        if (view.parent == null) return
        runCatching { windowManager.removeView(view) }
    }

    private fun tryAdd(type: Int, flags: Int): Boolean {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        return runCatching {
            windowManager.addView(view, params)
            view.requestFocus()
            view.parent != null
        }.getOrDefault(false)
    }
}
