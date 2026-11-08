package com.pagetime.app.blocker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.ConcurrentHashMap

/**
 * What [AppAllowlist] needs to know about installed apps, behind an interface
 * so the controller's decisions can be tested without a PackageManager.
 */
interface AppClassifier {
    /** Packages that are never blocked in allowlist mode. */
    fun essentials(): Set<String>

    /** Whether [packageName] has a launcher entry, i.e. is something a person opens. */
    fun isLaunchable(packageName: String): Boolean
}

/**
 * Resolves the essentials by Android role and intent rather than by name, so
 * they are right on Samsung, Pixel or anything else: the default dialer and
 * SMS app, every home screen, Settings, the clock, the keyboard.
 *
 * Both answers are cached. The accessibility service asks on the main thread
 * for every window change, and PackageManager calls are IPC; the essentials
 * are refreshed every [ESSENTIALS_TTL_MS] so changing the default SMS app or
 * keyboard is picked up without a restart.
 *
 * Fails toward allowing: a lookup that throws contributes nothing to the
 * essentials, but [isLaunchable] answers false on error, so an app that cannot
 * be inspected is never blocked on a guess.
 */
class AndroidAppClassifier(
    private val context: Context,
    private val selfPackage: String = context.packageName,
) : AppClassifier {

    private companion object {
        const val ESSENTIALS_TTL_MS = 5 * 60_000L

        /** Always present system surfaces, beside anything resolved by intent. */
        val SYSTEM = setOf("android", "com.android.systemui")
    }

    @Volatile
    private var cached: Set<String> = emptySet()

    @Volatile
    private var cachedAt = Long.MIN_VALUE

    private val launchable = ConcurrentHashMap<String, Boolean>()

    override fun essentials(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < ESSENTIALS_TTL_MS) return cached
        cached = resolveEssentials()
        cachedAt = now
        return cached
    }

    override fun isLaunchable(packageName: String): Boolean =
        launchable.getOrPut(packageName) {
            runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
                .getOrDefault(false)
        }

    private fun resolveEssentials(): Set<String> {
        val pm = context.packageManager
        val out = mutableSetOf(selfPackage)
        out += SYSTEM

        val telecom = context.getSystemService(TelecomManager::class.java)
        safe { telecom?.defaultDialerPackage }?.let(out::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            safe { telecom?.systemDialerPackage }?.let(out::add)
        }
        safe { Telephony.Sms.getDefaultSmsPackage(context) }?.let(out::add)

        out += handlers(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        out += handlers(pm, Intent(Settings.ACTION_SETTINGS))
        out += handlers(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
        out += handlers(pm, Intent(AlarmClock.ACTION_SET_ALARM))
        out += handlers(pm, Intent(Intent.ACTION_DIAL))

        // The current keyboard, and any other enabled one: a blocked keyboard
        // would leave no way to type into the apps that are allowed.
        safe {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }?.let(out::add)
        safe {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList?.map { it.packageName }
        }?.let(out::addAll)

        return out.filter { it.isNotBlank() }.toSet()
    }

    private fun handlers(pm: PackageManager, intent: Intent): List<String> =
        safe { pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } } ?: emptyList()

    private inline fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()
}
