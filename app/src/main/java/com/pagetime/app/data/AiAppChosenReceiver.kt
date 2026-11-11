package com.pagetime.app.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.launch

/**
 * Learns which app the reader sends their prompts to.
 *
 * The system share sheet reports the app that was picked, and remembering it
 * is what turns "share, find the app, tap it" into one button the next time.
 * Nothing is sent anywhere by this; it only records a choice the reader made.
 */
class AiAppChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val chosen = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) ?: return
        if (chosen.packageName == context.packageName) return
        val container = (context.applicationContext as? PageTimeApp)?.container ?: return
        val pending = goAsync()
        container.scope.launch {
            try {
                runCatching { container.settingsRepository.setAiApp(chosen.packageName, chosen.className) }
            } finally {
                pending.finish()
            }
        }
    }
}
