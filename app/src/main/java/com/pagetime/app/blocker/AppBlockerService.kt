package com.pagetime.app.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pagetime.app.MainActivity
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.review.ReviewSession
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.launch

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
            // A resumed browser is the one case an event is not guaranteed for:
            // some OEM skins do not fire a window-state event for a task that
            // was merely restored rather than freshly started. This poll is
            // the documented mitigation for that gap, not a license to recheck
            // on a timer — checkResumedSite only acts on the TRANSITION into a
            // browser, so it does nothing at all on every tick spent inside
            // one, and it still defers to the address bar being actively
            // edited for the same reason it always has.
            checkResumedSite(pkg)
            // The other direction of the same gap: a session that opened a
            // site can run out while the reader never left the page it was
            // covering, and nothing "happens" then either — no window
            // changes, no navigation, just a purchase running down to zero
            // mid-read. checkSiteAccessClosed is what BlockController's own
            // gate-flow collector already is for apps (react to the access
            // state closing, not only to a window changing), done here on the
            // poll because a site check has no equivalent collector of its
            // own to react from.
            checkSiteAccessClosed(pkg)
            // The gap those two miss between them: a site that a session
            // covers was only ever a gate, never a meter, so a reader could
            // pour the whole session into the one site it exists to make an
            // exception for and it cost nothing. This is what actually
            // spends the session on it, at the same cadence and for the same
            // reason checkResumedSite above reads the address bar at all.
            checkSiteCoverage(pkg)
            mainHandler.postDelayed(this, FOREGROUND_REFRESH_MS)
        }
    }
    private val navigationCheck = Runnable { checkSites(pendingNavigationPackage) }
    private val navigationCheckFollowUp = Runnable { checkSites(pendingNavigationPackage) }
    private val navigationCommitProbe = object : Runnable {
        override fun run() {
            val browserPackage = pendingNavigationPackage ?: return
            if (!inputMethodWindowVisible() && browserPackage in browsersWithUncommittedAddressEdit) {
                scheduleNavigationCheck(browserPackage)
            } else if (browserPackage in browsersWithUncommittedAddressEdit) {
                mainHandler.postDelayed(this, NAVIGATION_COMMIT_PROBE_DELAY_MS)
            }
        }
    }

    /** Browser package associated with the latest Enter/Go action. */
    private var pendingNavigationPackage: String? = null

    /**
     * Last URL observed in each browser's address bar. Accessibility does not
     * expose a universal "navigation committed" callback, so the service uses
     * the browser's own post-navigation content event as the commit signal.
     */
    private val lastBrowserUrls = mutableMapOf<String, String?>()

    /** Browsers whose address bar has been edited but not yet committed. */
    private val browsersWithUncommittedAddressEdit = mutableSetOf<String>()

    /**
     * The last trusted foreground package, so a RETURN to a browser can be
     * told apart from continuing to sit inside one. See [checkResumedSite].
     */
    private var lastForegroundPackage: String? = null

    /**
     * Whether [SiteBlocker] last reported a session covering sites, so its
     * running out can be told apart from access having been closed all
     * along. See [checkSiteAccessClosed]. Starts `false`, so the very first
     * poll after connecting only establishes the baseline rather than
     * manufacturing a transition out of nothing; a session already open at
     * that moment is separately handled by [checkResumedSite] not blocking,
     * which is the correct behaviour regardless of what this starts as.
     */
    private var lastSiteAccessOpen = false

    private var overlay: TimeUpOverlay? = null

    /**
     * The block screen for a SITE, which is a separate window from [overlay].
     *
     * Two instances rather than one shared, because they are re-pointed at
     * completely different content and only ever one of them is up. Sharing one
     * would mean every site block having to rebuild the app block's screen (or
     * worse, show the app block's last title) and the two reasons for blocking
     * would be indistinguishable the moment anything went wrong.
     */
    private var siteOverlay: TimeUpOverlay? = null

    /** The rule a block is currently resting on, or null when no site is blocked. */
    private var siteBlock: SiteRules.Rule? = null

    /** The browser whose address bar produced the current site block. */
    private var siteBrowserPackage: String? = null

    /**
     * When the site check last ran, and when it last paid for a tree walk.
     *
     * A Chromium page emits content-changed events continuously while it loads
     * and settles, and each one is an invitation to read the address bar. The
     * bar cannot have changed if the text has not, so the reading is rate
     * limited rather than trusted to the event stream.
     */
    private var lastSiteCheckAt = 0L
    private var lastTreeWalkAt = 0L

    /**
     * Packages that resolve a web link but turned out to have no address bar.
     *
     * Most apps that can open a URL are browsers; some are not, and the ones
     * that are not (a video app, a news app) are worth remembering so the tree
     * walk is paid for once rather than on every poll. Known browsers are never
     * remembered this way — a Chrome whose toolbar is hidden by a full-screen
     * video still has one.
     */
    private val barlessPackages = mutableSetOf<String>()

    /** Packages that can open a web link, and the ones that cannot. Both cached. */
    private val browserPackages = mutableSetOf<String>()
    private val nonBrowserPackages = mutableSetOf<String>()

    /** Wall-clock monotonic time a site screen cannot be drawn until (0 = never). */
    private var siteOverlayUnavailableUntil = 0L

    /**
     * Keeps the site screen up for as long as the browser is the app in front.
     *
     * The whole loop, because there is nothing else to assert. The address bar
     * cannot change while the screen covers it — the cover takes every touch —
     * so the only question left is whether the reader is still in the browser
     * at all, and the answer to that is the focused window and nothing more.
     * This is what takes the screen down when they press Home or switch apps.
     */
    private val siteEnforcement = object : Runnable {
        override fun run() {
            if (siteBlock == null) return
            val browser = siteBrowserPackage
            val front = focusedWindowPackage()
            when {
                browser == null -> {
                    clearSiteBlock()
                    return
                }
                front == browser -> Unit
                // Our own overlay, or a window that cannot be inspected.
                // Unknown is not evidence of leaving, so the block stands — the
                // same reading the app's enforce loop gives it.
                front == null || front == packageName -> Unit
                else -> {
                    clearSiteBlock()
                    return
                }
            }
            mainHandler.postDelayed(this, SITE_ENFORCE_INTERVAL_MS)
        }
    }

    private val controller: BlockController?
        get() = (application as? PageTimeApp)?.container?.blockController

    private val siteBlocker: SiteBlocker?
        get() = (application as? PageTimeApp)?.container?.siteBlocker

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
        // The service can (re)connect — after a reboot, or the system killing
        // and restarting it under memory pressure — while a browser is already
        // sitting on a page a rule covers. Nothing else will ever ask about
        // that page if this does not: every other check below is keyed on an
        // event, and there will not be one.
        checkResumedSite(pkg)
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
                checkResumedSite(inFront)
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

        // Chrome does not consistently expose its Go button or the IME key to
        // accessibility services. Observe the browser's URL state as a second,
        // platform-compatible commit signal: text edits are remembered only;
        // the first browser content/window event after editing has ended is a
        // real navigation attempt and may trigger the rule check.
        observeBrowserNavigation(event)

        // A click on the browser's Go/Search/Navigate control is the other
        // navigation commit path. Ordinary address-bar clicks and typing do not
        // qualify, so the reader can edit or replace a URL without being
        // interrupted by the block screen.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            navigationCommit(event)
        ) {
            scheduleNavigationCheck(currentBrowser()?.packageName)
        }
    }

    /**
     * Catches the blocked page a reader is handed back, rather than one they
     * navigate to.
     *
     * NO NEW MECHANISM, THE DOCUMENTED ONE ASKED A DIFFERENT QUESTION
     *
     * `TYPE_WINDOW_STATE_CHANGED` is the platform's own signal for "the active
     * window changed" — it is what [ForegroundEventPolicy] already uses to
     * decide an app has come to the front, and it fires for a resumed task
     * exactly as it does for a freshly launched one. This is the same
     * foreground signal the file already trusts elsewhere, asked a question
     * the navigation-commit path never gets to answer.
     *
     * THE GAP THIS CLOSES
     *
     * Every check above this one — [observeBrowserNavigation], the key event,
     * the Go-button click — fires from something HAPPENING: typing, a tap, a
     * page settling after Enter. A browser sent to the back with a blocked
     * page open and then brought back to the front has nothing happen to it at
     * all. Its address bar reads exactly what it read before, so the existing
     * `urlChanged` check — correctly built to avoid re-blocking a page the
     * reader is simply continuing to look at — sees no change and stays
     * silent. Leave the browser on the blocked page, come back to it, and
     * nothing ever asked the address bar again. That is the reported bug.
     *
     * WHY THIS IS KEYED ON THE TRANSITION, NOT ON BEING THERE
     *
     * [ForegroundEventPolicy.enteredForeground] is what keeps this from
     * fighting the polled backstop below: it fires once, on the return, and
     * does nothing for as long as the reader keeps sitting in the same
     * browser — the case `urlChanged` already owns. And it steps aside for an
     * address bar that is being actively edited, for the same reason every
     * other check here does: a reader mid-edit has not asked to go anywhere
     * yet.
     */
    private fun checkResumedSite(rawForegroundPackage: String?) {
        val trusted = rawForegroundPackage?.takeIf {
            ForegroundEventPolicy.isTrustedForegroundPackage(it, packageName)
        } ?: return
        val entered = ForegroundEventPolicy.enteredForeground(lastForegroundPackage, trusted)
        lastForegroundPackage = trusted
        if (!entered || !isBrowserPackage(trusted)) return

        val browser = currentBrowser() ?: return
        if (browser.packageName != trusted) return
        if (inputMethodWindowVisible() && addressBarHasInputFocus(browser)) return
        scheduleNavigationCheck(trusted)
    }

    /**
     * Catches a session running out while the reader never left the page it
     * was covering.
     *
     * A SESSION COVERING SITES IS THE SAME FREE PASS AN APP GETS
     *
     * [SiteBlocker.accessOpen] mirrors the gate the exact way
     * [com.pagetime.app.domain.BalanceManager.gate] already does for apps,
     * and [BlockController] reacts to THAT collector directly — every tick,
     * whether or not the reader has moved at all, because the gate ticks on
     * its own. A site check has no equivalent of its own to react from: [checkSites] only
     * ever runs from a navigation-commit path or from [checkResumedSite]'s
     * foreground-entry edge, neither of which fires while someone sits
     * still. This poll is what stands in for that missing collector.
     *
     * WHY THIS IS NOT THE SAME SHAPE AS [checkResumedSite]
     *
     * That one is edge-triggered on the FOREGROUND PACKAGE changing. This is
     * edge-triggered on ACCESS CLOSING while the foreground package does not
     * need to change at all — the whole point is the reader stayed exactly
     * where they were. Firing on every tick while access stays closed would
     * be the timer-based re-check this file has avoided from the start; only
     * the transition is worth a check.
     */
    private fun checkSiteAccessClosed(foregroundPackage: String?) {
        val open = siteBlocker?.accessOpen ?: return
        val justClosed = lastSiteAccessOpen && !open
        lastSiteAccessOpen = open
        if (!justClosed) return
        if (foregroundPackage == null || !isBrowserPackage(foregroundPackage)) return
        scheduleNavigationCheck(foregroundPackage)
    }

    /**
     * Tells [BlockController] which site, if any, the reader is currently
     * spending a session on.
     *
     * A NEW IDENTITY EACH TICK, NOT A NEW SECOND-BY-SECOND TIMER
     *
     * The actual decrement is [BlockController]'s own job, on its own 1s
     * clock, started once and left running until this reports a change —
     * exactly the shape [checkResumedSite] already relies on for a blocked
     * app's ticker. This only has to confirm, at the poll's own 2s cadence,
     * which rule (or none) currently covers the address bar; it does not
     * need to run any faster than that for the same reason [checkResumedSite]
     * does not: the ticker it feeds keeps its own time between calls.
     *
     * This is a genuinely new read every poll tick, but a bounded one. It
     * only happens while a session is open AND the reader is in a browser.
     * [readUrlBar]'s fast path (a known browser's view id) is a single
     * lookup, and its slow path (an unknown browser's tree walk) is already
     * throttled to [TREE_WALK_MIN_INTERVAL_MS] regardless of who calls it —
     * this cannot make that walk run any more often than it already could.
     */
    private fun checkSiteCoverage(foregroundPackage: String?) {
        val blocker = siteBlocker ?: return
        if (!blocker.accessOpen || foregroundPackage == null || !isBrowserPackage(foregroundPackage)) {
            controller?.onSiteCoverage(null)
            return
        }
        val browser = currentBrowser()
        if (browser == null || browser.packageName != foregroundPackage) {
            controller?.onSiteCoverage(null)
            return
        }
        val bar = readUrlBar(browser.root, browser.packageName)
        controller?.onSiteCoverage(bar?.let { blocker.wouldMatch(it) }?.id)
    }

    /**
     * Converts the browser's accessibility event stream into a navigation
     * commit without treating ordinary URL editing as one.
     *
     * Chrome exposes address-bar typing as text events, but often does not
     * expose the soft keyboard's Go action or a toolbar button. After a link,
     * Enter, or Go causes navigation, Chromium emits a window/content event and
     * the address bar is no longer the actively edited field. That transition
     * is the reliable signal available to an accessibility service.
     */
    private fun observeBrowserNavigation(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        val browser = currentBrowser() ?: return
        val browserPackage = browser.packageName
        val inputMethodPackage = if (eventPackage == browserPackage) {
            null
        } else {
            inputMethodPackage()
        }
        if (eventPackage != browserPackage &&
            eventPackage != inputMethodPackage &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val root = browser.root
        val url = readUrlBar(root, browserPackage)
        val source = runCatching { event.source }.getOrNull()
        val sourceNode = source?.let {
            BrowserUrlBars.Node(
                viewId = runCatching { it.viewIdResourceName }.getOrNull(),
                text = it.text?.toString(),
                contentDescription = it.contentDescription?.toString(),
                className = it.className?.toString(),
            )
        }
        val sourceIsAddressBar = sourceNode?.let {
            BrowserUrlBars.isAddressBar(it, browserPackage)
        } == true
        val addressBarFocused = addressBarHasInputFocus(browser)
        val isTextEditEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        val keyboardVisible = inputMethodWindowVisible()
        val relevantWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val isBrowserCommitEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            relevantWindowChange

        // A keyboard click is the strongest signal when the IME exposes one.
        // It is deliberately checked before focus state: the browser can keep
        // reporting its address field as focused for a few frames after Go.
        if (eventPackage != browserPackage &&
            BrowserUrlBars.isInputMethodNavigationCommitAction(sourceNode ?: return)
        ) {
            if (addressBarFocused) scheduleNavigationCheck(browserPackage)
            lastBrowserUrls[browserPackage] = url
            return
        }

        // The source may still point at the URL field during the first browser
        // event after Go. That event is not editing merely because Chrome kept
        // the same accessibility source; only a text-change event or a visible
        // keyboard means the user is currently editing.
        val addressBarEdit = sourceIsAddressBar || addressBarFocused
        if (isTextEditEvent || (addressBarFocused && keyboardVisible)) {
            if (addressBarEdit) {
                browsersWithUncommittedAddressEdit += browserPackage
            }
            lastBrowserUrls[browserPackage] = url
            return
        }

        // A click anywhere in the browser other than the address field can
        // activate a link, reload, tab, or form navigation. Chrome often emits
        // no semantic "Go" event for these, so use the click as a navigation
        // intent and let the delayed URL read decide whether the destination is
        // blocked. Clicking the address bar itself remains editing only.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventPackage == browserPackage &&
            !sourceIsAddressBar
        ) {
            scheduleNavigationCheck(browserPackage)
        }

        val previousUrl = lastBrowserUrls[browserPackage]
        val urlChanged = previousUrl != null && url != null && url != previousUrl
        val hasPendingAddressEdit = browserPackage in browsersWithUncommittedAddressEdit
        // Once the keyboard has gone away, a changed address bar or the first
        // browser content/state event after an edit is the navigation commit.
        // This catches links as well as Go on keyboards that expose no
        // accessibility click and browsers that expose no Go button.
        if (isBrowserCommitEvent && !keyboardVisible && (urlChanged || hasPendingAddressEdit)) {
            mainHandler.removeCallbacks(navigationCommitProbe)
            scheduleNavigationCheck(browserPackage)
            browsersWithUncommittedAddressEdit.remove(browserPackage)
        } else if (isBrowserCommitEvent && keyboardVisible && hasPendingAddressEdit) {
            // Keep the marker: Go may have started, but the IME is still visible
            // and the address bar has not settled yet.
            mainHandler.removeCallbacks(navigationCommitProbe)
            mainHandler.postDelayed(navigationCommitProbe, NAVIGATION_COMMIT_PROBE_DELAY_MS)
        }
        lastBrowserUrls[browserPackage] = url
    }

    /**
     * Receives Enter from a focused browser address bar without consuming it.
     *
     * Accessibility services only receive filtered key events when the service
     * declares flagRequestFilterKeyEvents. Returning false lets the browser
     * handle Enter normally; the delayed check runs after it has committed the
     * new address and updated its accessibility tree.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            val browser = currentBrowser()
            if (browser != null && addressBarHasInputFocus(browser)) {
                scheduleNavigationCheck(browser.packageName)
            }
        }
        return false
    }

    private fun scheduleNavigationCheck(browserPackage: String?) {
        if (browserPackage.isNullOrBlank()) return
        pendingNavigationPackage = browserPackage
        mainHandler.removeCallbacks(navigationCheck)
        mainHandler.removeCallbacks(navigationCheckFollowUp)
        // The first pass catches fast Chromium navigations; the follow-up covers
        // browsers that update the address bar only after the first page event.
        mainHandler.postDelayed(navigationCheck, NAVIGATION_CHECK_DELAY_MS)
        mainHandler.postDelayed(navigationCheckFollowUp, NAVIGATION_CHECK_FOLLOW_UP_MS)
    }

    private fun navigationCommit(event: AccessibilityEvent): Boolean {
        val browser = currentBrowser() ?: return false
        val eventPackage = event.packageName?.toString() ?: return false
        val source = runCatching { event.source }.getOrNull() ?: return false
        val node = BrowserUrlBars.Node(
            viewId = runCatching { source.viewIdResourceName }.getOrNull(),
            text = source.text?.toString(),
            contentDescription = source.contentDescription?.toString(),
            className = source.className?.toString(),
        )

        if (eventPackage == browser.packageName) {
            return BrowserUrlBars.isNavigationCommitAction(node)
        }

        // Soft-keyboard actions are reported by the IME package, not by Chrome
        // or Firefox. Only accept them while the browser address bar is focused;
        // this prevents a page's own Search button from becoming a navigation
        // commit merely because it has the same visible label.
        val inputMethodPackage = inputMethodPackage() ?: return false
        return eventPackage == inputMethodPackage &&
            addressBarHasInputFocus(browser) &&
            BrowserUrlBars.isInputMethodNavigationCommitAction(node)
    }

    private fun addressBarHasInputFocus(browser: BrowserWindow): Boolean {
        val focused = runCatching { browser.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull() ?: return false
        return BrowserUrlBars.isAddressBar(
            BrowserUrlBars.Node(
                viewId = runCatching { focused.viewIdResourceName }.getOrNull(),
                text = focused.text?.toString(),
                contentDescription = focused.contentDescription?.toString(),
                className = focused.className?.toString(),
            ),
            browser.packageName,
        )
    }

    /** A browser root remains inspectable while its keyboard owns focus. */
    private fun currentBrowser(): BrowserWindow? {
        val direct = rootInActiveWindow
        val directPackage = direct?.packageName?.toString()
        if (direct != null && directPackage != null && isBrowserPackage(directPackage)) {
            return BrowserWindow(directPackage, direct)
        }

        val candidates = runCatching {
            windows.mapNotNull { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                val packageName = root.packageName?.toString() ?: return@mapNotNull null
                if (!isBrowserPackage(packageName)) return@mapNotNull null
                BrowserWindow(packageName, root)
            }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull()
    }

    private fun inputMethodPackage(): String? = runCatching {
        windows.firstNotNullOfOrNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@firstNotNullOfOrNull null
            window.root?.packageName?.toString()
        }
    }.getOrNull()

    private fun inputMethodWindowVisible(): Boolean = runCatching {
        // An IME can remain in the interactive-window list briefly after its
        // animation ends. Active/focused is the useful distinction: while the
        // user is typing the keyboard owns one of those states; after Go it
        // loses both and the pending navigation can be checked.
        windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                (window.isActive || window.isFocused)
        }
    }.getOrDefault(false)

    private data class BrowserWindow(
        val packageName: String,
        val root: AccessibilityNodeInfo,
    )

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(foregroundRefresh)
        mainHandler.removeCallbacks(navigationCheck)
        mainHandler.removeCallbacks(navigationCheckFollowUp)
        mainHandler.removeCallbacks(navigationCommitProbe)
        pendingNavigationPackage = null
        lastBrowserUrls.clear()
        browsersWithUncommittedAddressEdit.clear()
        lastForegroundPackage = null
        lastSiteAccessOpen = false
        dismissTimeUp()
        clearSiteBlock()
        controller?.service = null
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_REFRESH_MS = 2_000L

        /** Delay before checking the URL after Enter/Go. */
        private const val NAVIGATION_CHECK_DELAY_MS = 250L

        /** Retry after the keyboard has had time to leave the window list. */
        private const val NAVIGATION_COMMIT_PROBE_DELAY_MS = 350L

        /** Follow-up for browsers whose accessibility tree updates slowly. */
        private const val NAVIGATION_CHECK_FOLLOW_UP_MS = 900L

        /** How often the site loop re-checks that the browser is still in front. */
        private const val SITE_ENFORCE_INTERVAL_MS = 1_000L

        /**
         * The floor between two address bar reads.
         *
         * A loaded page fires content-changed events far faster than this. The
         * bar's text is the only thing worth reacting to, and it cannot change
         * more often than a person can press a key.
         */
        private const val SITE_CHECK_MIN_INTERVAL_MS = 400L

        /**
         * The floor between two full tree walks.
         *
         * The walk is the fallback for a browser that does not answer to a
         * view id lookup — an unknown browser, or a known one whose toolbar is
         * hidden behind full-screen content. Bounded and throttled because
         * it is the one part of this that costs anything.
         */
        private const val TREE_WALK_MIN_INTERVAL_MS = 1_500L

        /** Depth-bounded node budget for a tree walk; the toolbar is near the top. */
        private const val MAX_WALKED_NODES = 400

        /** How many address-bar-less apps to remember before giving up on memoising. */
        private const val MAX_REMEMBERED_BARLESS = 64

        /**
         * How long to stop trying after no site screen could be drawn.
         *
         * An accessibility overlay needs no permission and effectively always
         * attaches, so this is the "something is very wrong" path. Without a
         * pause the service would re-decide every few hundred milliseconds for
         * a screen it cannot draw, and would hold a block that is not real.
         */
        private const val SITE_OVERLAY_RETRY_MS = 60_000L

        /**
         * The gap between dropping the site screen and pressing Back.
         *
         * The screen is focusable and swallows Back on purpose, so the press
         * has to be sent after it is gone or it lands on the screen itself and
         * does nothing.
         */
        private const val SITE_BACK_DELAY_MS = 200L
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
        // The app block is the broader rule: it says the browser itself is off
        // limits, which makes any opinion about the page inside it moot — and
        // two block screens stacked on each other is one screen the reader
        // cannot read.
        clearSiteBlock()
        val current = overlay ?: TimeUpOverlay(
            context = this,
            onReadNow = { openReader() },
            onStartSession = { controller?.startSessionFromBlockScreen() },
            onEmergency = { controller?.useEmergencyUnlock() },
        ).also { overlay = it }
        val gate = controller?.gate ?: GateState.Unknown
        // Refreshed on every show rather than only on creation: the overlay is
        // re-used across blocks, and under the gate the number on it changes
        // every minute the reader spends reading.
        current.setStatus(gate, emergencyOffer(gate))
        return current.show()
    }

    /**
     * The hatch, if it is on offer.
     *
     * Withheld entirely when a session is already affordable: an escape route
     * next to an unlocked door teaches the reader to take the escape.
     */
    private fun emergencyOffer(gate: GateState): TimeUpOverlay.EmergencyOffer? {
        val c = controller ?: return null
        if (gate.canStartSession) return null
        // A hard lock hides it altogether rather than showing a refusal: the
        // reader chose that lock and does not need arguing with. Merely having
        // spent both for today still shows the button, disabled, saying when
        // the next one is back — that is information, not temptation.
        val hardLocked = !c.canUseEmergency() && c.emergencyUsesLeft() > 0
        if (hardLocked) return null
        val pkg = c.currentBlockedPackage ?: return null
        return TimeUpOverlay.EmergencyOffer(
            appLabel = labelFor(pkg),
            usesLeft = c.emergencyUsesLeft(),
            nextAvailableInSeconds = c.emergencyNextAvailableInSeconds(),
        )
    }

    /** The app's own name, so the button can say what it will open. */
    private fun labelFor(packageName: String): String? = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

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

    // ---------------------------------------------------------------------
    // Blocked sites
    // ---------------------------------------------------------------------

    /**
     * Looks at the address bar in front and blocks the page if a rule covers it.
     *
     * Called only after an explicit navigation commit (Enter/Go), rather than
     * on ordinary address-bar edits or a timer. Cheap to call and cheap to
     * ignore: it does nothing at all unless the focused window is a browser,
     * which is what keeps this from reading the screen of every app on the phone.
     */
    private fun checkSites(expectedBrowserPackage: String? = null) {
        // An app block owns the screen; there is no page to have an opinion
        // about while the app itself is refused.
        if (isTimeUpShowing()) return
        if (siteBlock != null) return
        val now = SystemClock.elapsedRealtime()
        if (now < siteOverlayUnavailableUntil) return
        if (now - lastSiteCheckAt < SITE_CHECK_MIN_INTERVAL_MS) return
        lastSiteCheckAt = now

        val browser = currentBrowser() ?: return
        if (expectedBrowserPackage != null && browser.packageName != expectedBrowserPackage) return
        val root = browser.root
        val pkg = browser.packageName
        val bar = readUrlBar(root, pkg) ?: return
        val rule = siteBlocker?.match(bar) ?: return
        startSiteBlock(rule, pkg)
    }

    /**
     * Whether [packageName] can open a web link.
     *
     * The table answers for every browser worth naming. This answers for the
     * ones nobody has named yet, using the one property every browser has and
     * almost nothing else does: it handles an `https://` intent. That is the
     * same question the address bar itself answers for the reader, which is why
     * it is the right one to ask — a package that cannot open a web link has no
     * address bar to read.
     *
     * Resolved once per package. The answer cannot change while the app runs,
     * and asking the PackageManager on every accessibility event would be the
     * kind of cost that only shows up on the devices this has to work on.
     */
    private fun isBrowserPackage(packageName: String): Boolean {
        if (BrowserUrlBars.isKnownBrowser(packageName)) return true
        if (packageName in browserPackages) return true
        if (packageName in nonBrowserPackages) return false
        val resolves = runCatching {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            packageManager.queryIntentActivities(probe, 0)
                .any { it.activityInfo?.packageName == packageName }
        }.getOrDefault(false)
        if (resolves) browserPackages += packageName else nonBrowserPackages += packageName
        return resolves
    }

    /**
     * The text in [root]'s address bar, or null when there is not one.
     *
     * TABLE FIRST, TREE SECOND
     *
     * A known browser is asked for the one or two ids the table gives it, which
     * is a single lookup each and answers immediately. Only if that comes back
     * empty does this pay for a walk of the window — which is what makes an
     * unknown browser work at all, and what makes a Chrome whose toolbar is
     * hidden by a full-screen video stop costing anything after the first
     * second and a half.
     */
    private fun readUrlBar(root: AccessibilityNodeInfo, packageName: String): String? {
        val spec = BrowserUrlBars.specFor(packageName)
        if (spec != null) {
            val hits = spec.ids.mapNotNull { id -> nodeForId(root, id) }
            BrowserUrlBars.pickUrl(hits, packageName)?.let { return it }
        } else if (packageName in barlessPackages) {
            // An app that can open a link but has no bar, seen before. Nothing
            // about it will have changed.
            return null
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastTreeWalkAt < TREE_WALK_MIN_INTERVAL_MS) return null
        lastTreeWalkAt = now

        val url = BrowserUrlBars.pickUrl(walkTree(root), packageName)
        if (url == null &&
            !BrowserUrlBars.isKnownBrowser(packageName) &&
            barlessPackages.size < MAX_REMEMBERED_BARLESS
        ) {
            barlessPackages += packageName
        }
        return url
    }

    /** One node by view id, or null when the window has no such node. */
    private fun nodeForId(root: AccessibilityNodeInfo, id: String): BrowserUrlBars.Node? =
        runCatching { root.findAccessibilityNodeInfosByViewId(id) }
            .getOrNull()
            ?.firstOrNull()
            ?.let { node ->
                BrowserUrlBars.Node(
                    viewId = id,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                )
            }

    /**
     * A bounded breadth-first read of the window.
     *
     * Breadth-first and bounded for one reason: an address bar lives near the
     * top of a browser's hierarchy, while the page inside it can be tens of
     * thousands of nodes deep. Walking the whole tree to find a toolbar would
     * be the one operation here that a long article could make slow.
     */
    private fun walkTree(root: AccessibilityNodeInfo): List<BrowserUrlBars.Node> {
        val out = ArrayList<BrowserUrlBars.Node>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < MAX_WALKED_NODES) {
            val node = queue.removeFirst()
            out += BrowserUrlBars.Node(
                viewId = runCatching { node.viewIdResourceName }.getOrNull(),
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
            )
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return out
    }

    /**
     * Puts the site screen up and starts the loop that keeps it honest.
     *
     * The browser's meter is paused for as long as this is up. The existing
     * rule is that time is not charged when nobody is looking — the screen-off
     * check — and a cover over the page is exactly that situation: the reader
     * is not using the app, so charging them for it would be the same theft the
     * screen-off rule exists to prevent.
     */
    private fun startSiteBlock(rule: SiteRules.Rule, browserPackage: String) {
        siteBlock = rule
        siteBrowserPackage = browserPackage
        siteBlocker?.logBlocked(rule)
        controller?.sitePaused = true

        if (!showSiteBlock(rule)) {
            // No overlay window could be added — a permission revoked under us,
            // or an OEM that refuses accessibility overlays. Claiming a block
            // that cannot be drawn would leave the browser unmetered with
            // nothing on screen to explain it, so the block is given up and
            // the attempt is paused rather than repeated every 400ms.
            siteOverlayUnavailableUntil = SystemClock.elapsedRealtime() + SITE_OVERLAY_RETRY_MS
            clearSiteBlock()
            return
        }

        mainHandler.removeCallbacks(siteEnforcement)
        mainHandler.postDelayed(siteEnforcement, SITE_ENFORCE_INTERVAL_MS)
    }

    private fun showSiteBlock(rule: SiteRules.Rule): Boolean {
        val current = siteOverlay ?: TimeUpOverlay(
            context = this,
            onReadNow = { openReader() },
            onSiteBack = { leaveSiteBlock() },
        ).also { siteOverlay = it }
        current.setSiteBlock(rule)
        return current.show()
    }

    /**
     * Takes the site screen down and steps back out of the page.
     *
     * Back rather than Home, and back rather than the reader: the reader is
     * already on offer as the screen's main button, and the reason a reader
     * meets this screen at all is usually a link they followed. Undoing that
     * link is the smaller, more useful act — and it is the only one that leaves
     * them where they were rather than somewhere new.
     */
    private fun leaveSiteBlock() {
        clearSiteBlock()
        mainHandler.postDelayed(
            { performGlobalAction(GLOBAL_ACTION_BACK) },
            SITE_BACK_DELAY_MS
        )
    }

    /** Drops any site block, stops its loop, and unpauses the browser's meter. */
    private fun clearSiteBlock() {
        val wasBlocking = siteBlock != null
        siteBlock = null
        siteBrowserPackage = null
        mainHandler.removeCallbacks(siteEnforcement)
        siteOverlay?.dismiss()
        if (wasBlocking) controller?.sitePaused = false
    }

    /**
     * Opens the reader, aimed at the next due chunk when there is one.
     *
     * The read-to-unlock loop and the re-read loop are the same loop: when the
     * balance is empty the reader is the door, so it should open on the passage
     * the schedule says is due rather than wherever the reader last stopped.
     *
     * The chunk is resumed (state READING, pending source written) BEFORE the
     * activity launches, so the reader cannot load before its start position
     * exists and open at the wrong place. Without a due chunk the intent carries
     * no book id and the reader opens on the last book, exactly as before.
     */
    private fun openReader() {
        controller?.releaseBlock()
        clearSiteBlock()
        dismissTimeUp()
        val app = application as? PageTimeApp
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_READER, true)
        }
        val repository = app?.container?.pagemarkRepository
        val scope = app?.container?.scope
        if (repository == null || scope == null) {
            // No app container to consult; plain open, as before.
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // Background activity launch refused; the user is at least out of the app.
            }
            return
        }
        // The same lookahead the review sitting uses: a chunk due this evening
        // is worth offering while the reader is here.
        val threshold = ReviewSession.dueThreshold(System.currentTimeMillis())
        scope.launch {
            val chunk = runCatching { repository.dueChunks(threshold).firstOrNull() }.getOrNull()
            if (chunk != null) {
                runCatching { repository.resumeChunk(chunk.id) }
                intent.putExtra(MainActivity.EXTRA_OPEN_READER_BOOK_ID, chunk.bookId)
            }
            mainHandler.post {
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    // Background activity launch refused; the user is at least out of the app.
                }
            }
        }
    }
}
