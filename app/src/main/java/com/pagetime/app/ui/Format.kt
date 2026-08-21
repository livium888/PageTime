package com.pagetime.app.ui

import java.util.Locale

fun formatClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun formatMinutes(totalSeconds: Long): String {
    val m = totalSeconds / 60
    return if (m >= 1) "$m min" else "<1 min"
}
