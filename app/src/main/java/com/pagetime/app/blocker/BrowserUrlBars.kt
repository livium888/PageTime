package com.pagetime.app.blocker

/**
 * Where each browser keeps its address bar, and how to recognise one in a
 * browser the table has never heard of.
 *
 * TWO LOOKUPS, BECAUSE NEITHER ALONE IS ENOUGH
 *
 * The table is the fast path and it is not a guess: browsers on Android are
 * overwhelmingly Chromium, and every Chromium browser names its address bar
 * `{package}:id/url_bar`. Chrome, Brave, Edge, Vivaldi, Cromite, Vanadium and
 * the rest differ in everything except that. Opera moved it to `url_field`,
 * Samsung Internet has shipped two names, DuckDuckGo calls it an omnibar, and
 * the Firefox family is a different lineage again — it uses a mozac toolbar
 * id, and on some builds the bar has no usable id at all and is only findable
 * by its content description.
 *
 * The second lookup is a convention test over whatever nodes the window
 * actually offers. A browser released tomorrow, or one whose Chrome-derived
 * ui was renamed, is still almost certainly going to call the thing a
 * `url_bar` — and a rule the reader cannot count on is worse than no rule,
 * because they will only discover it failed by walking into the site. So the
 * table gets the answer right cheaply and the convention keeps it right when
 * the table is stale.
 *
 * WHAT THIS DOES NOT DO
 *
 * It does not read a URL out of a WebView. Android exposes no address for
 * web content, so a link opened inside Reddit or Instagram is not reachable
 * this way at all. That is a limit of the platform, not of this table, and
 * pretending otherwise would make the feature look more complete than it is.
 */
object BrowserUrlBars {

    /** Where one browser's address bar lives. */
    data class Spec(
        /** Fully-qualified view ids, most likely first. */
        val ids: List<String>,
        /** Content descriptions that name the bar, for bars with no usable id. */
        val contentDescriptions: List<String> = emptyList(),
    )

    /**
     * One node of a browser window, as the service saw it.
     *
     * Flattened to these three fields so the decision — which node is the
     * address bar — can be tested without an accessibility tree, which is the
     * only reason any of this is checkable off-device.
     */
    data class Node(
        val viewId: String?,
        val text: String?,
        val contentDescription: String?,
        /** The widget class, used only to tell a text field from a label. */
        val className: String? = null,
    )

    /** Firefox's address bar content description on builds that expose one. */
    private const val ADDRESS_BAR_DESCRIPTION = "ADDRESSBAR_URL_BOX"

    private val CHROMIUM_URL_BAR_IDS = listOf("url_bar")

    private val NAVIGATION_ID_SEGMENTS = listOf(
        "go",
        "go_button",
        "navigate",
        "navigate_button",
        "url_go",
        "url_go_button",
        "submit",
    )

    private val NAVIGATION_DESCRIPTIONS = listOf(
        "Go",
        "Navigate",
        "Load page",
        "Submit",
    )

    private val NAVIGATION_LABELS = listOf("Go", "Navigate", "Load")

    private val CHROMIUM_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "com.microsoft.emmx.canary",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.kiwibrowser.browser",
        "com.ecosia.android",
        "com.qwant.liberty",
        "com.cake.browser",
        "com.transsion.phoenix",
        "app.vanadium.browser",
        "org.cromite.cromite",
        "io.github.ungoogled_software.ungoogled_chromium",
    )

    /** Firefox and its forks. Same engine, same mozac toolbar. */
    private val FIREFOX_PACKAGES = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "us.spotco.fennec_dos",
        "org.ironfoxoss.ironfox",
        "io.github.forkmaintainers.iceraven",
    )

    /** Opera moved its address bar off the Chromium name. */
    private val OPERA_PACKAGES = listOf(
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
    )

    private val SAMSUNG_PACKAGES = listOf(
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
    )

    private val DUCKDUCKGO_PACKAGES = listOf("com.duckduckgo.mobile.android")

    /** OEM browsers: Chromium underneath, relabelled on top. */
    private val OEM_PACKAGES = listOf(
        "com.heytap.browser",
        "com.coloros.browser",
        "com.miui.browser",
        "com.android.browser",
    )

    /** Ids worth asking for in ANY package, after the table's own. */
    private val CONVENTION_URL_BAR_IDS = listOf(
        "url_bar",
        "url_field",
        "url_view",
        "url_edit_text",
        "address_bar_edit",
        "omnibar_text_input",
        "location_bar_edit_text",
    )

    /**
     * The last segment of an address-bar view id.
     *
     * Matched as a whole segment or as a suffix, so `url_bar`,
     * `search_url_bar` and `mozac_browser_toolbar_url_view` all qualify while
     * `url_bar_background` does not become one by accident.
     */
    private val ADDRESS_BAR_ID_SEGMENTS = listOf(
        "url_bar",
        "url_field",
        // The Firefox family's own name for it, which is why the unknown-browser
        // path still recognises a Firefox build the table has never seen.
        "url_view",
        "url_edit_text",
        "address_bar_edit",
        "omnibar_text_input",
        "location_bar_edit_text",
    )

    /** Content descriptions that name an address bar rather than a button. */
    private val ADDRESS_BAR_DESCRIPTIONS = listOf(
        ADDRESS_BAR_DESCRIPTION,
        "Search or enter address",
        "Search or type URL",
        "Address bar",
        "Search or enter website",
    )

    val BROWSERS: Map<String, Spec> = buildTable()

    /** The table's entry for [packageName], or null if it is not a known browser. */
    fun specFor(packageName: String): Spec? = BROWSERS[packageName]

    fun isKnownBrowser(packageName: String): Boolean = BROWSERS.containsKey(packageName)

    /**
     * The view ids to ask a window for, most specific first.
     *
     * The table's own ids come first so a browser that lists its bar under two
     * names is read through the right one; the conventions follow, so an
     * unknown browser is still looked for in the places a browser would put it.
     */
    fun candidateIds(packageName: String): List<String> {
        val explicit = BROWSERS[packageName]?.ids.orEmpty()
        val convention = CONVENTION_URL_BAR_IDS.map { "$packageName:id/$it" }
        return (explicit + convention).distinct()
    }

    /** Whether a node looks like an address bar, whatever the browser is. */
    fun looksLikeAddressBar(node: Node): Boolean {
        val id = node.viewId?.substringAfterLast('/')?.lowercase()
        if (id != null && ADDRESS_BAR_ID_SEGMENTS.any { id == it || id.endsWith("_$it") }) {
            return true
        }
        val description = node.contentDescription?.trim()
        return description != null &&
            ADDRESS_BAR_DESCRIPTIONS.any { it.equals(description, ignoreCase = true) }
    }

    /** Whether [node] is a browser control that commits the current address. */
    fun isNavigationCommitAction(node: Node): Boolean {
        val id = node.viewId?.substringAfterLast('/')?.lowercase().orEmpty()
        val description = node.contentDescription?.trim().orEmpty()
        val text = node.text?.trim().orEmpty()
        return NAVIGATION_ID_SEGMENTS.any { id == it || id.endsWith("_$it") } ||
            NAVIGATION_DESCRIPTIONS.any { it.equals(description, ignoreCase = true) } ||
            NAVIGATION_LABELS.any { it.equals(text, ignoreCase = true) }
    }

    /** Whether [node] is the address bar for [packageName] and can receive Enter. */
    fun isAddressBar(node: Node, packageName: String): Boolean {
        val spec = specFor(packageName)
        val id = node.viewId
        if (id != null && spec?.ids?.contains(id) == true) return true
        return looksLikeAddressBar(node)
    }

    /**
     * The address bar's text, or null when no node in [nodes] is one.
     *
     * Order is the whole function. The table's ids are asked for first because
     * a browser that has told us where its bar is should be believed; the
     * content descriptions follow, because that is all a Firefox build offers;
     * and the convention comes last, so it can only ever supply an answer the
     * specific knowledge did not.
     *
     * Blank text is not an answer. An empty address bar is what a new tab
     * looks like, and returning it would mean a rule matched against nothing.
     */
    fun pickUrl(nodes: List<Node>, packageName: String): String? {
        val spec = specFor(packageName)

        for (id in spec?.ids.orEmpty()) {
            val text = nodes.firstOrNull { it.viewId == id }?.text?.trim()
            if (!text.isNullOrEmpty()) return text
        }

        for (description in spec?.contentDescriptions.orEmpty()) {
            val text = nodes
                .firstOrNull { it.contentDescription?.trim().equals(description, ignoreCase = true) }
                ?.text
                ?.trim()
            if (!text.isNullOrEmpty()) return text
        }

        val byId = nodes.firstOrNull { looksLikeAddressBar(it) }?.text?.trim()
        if (!byId.isNullOrEmpty()) return byId

        // The bar can be readable but id-less and description-less, in which
        // case the only evidence left is that it is a TEXT FIELD whose contents
        // read like a web address. Both halves of that are required. Without
        // the text-field test this would happily accept whatever else on the
        // screen happens to look like a host — a bookmark, a most-visited tile
        // on the new tab page, a tab in the switcher — and block a page the
        // reader is not on.
        return nodes
            .asSequence()
            .filter { isTextEntry(it) }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { SiteRules.parseUrl(it) != null }
    }

    /**
     * Whether a node is a text entry widget rather than a label.
     *
     * Deliberately a class-name test and not a `className == "android.widget.EditText"`
     * one: every browser subclasses the text field, and which of the four or
     * five ancestors it reports varies by OEM and by version.
     */
    private fun isTextEntry(node: Node): Boolean {
        val name = node.className ?: return false
        return name.contains("EditText", ignoreCase = true) ||
            name.contains("UrlBar", ignoreCase = true) ||
            name.contains("TextField", ignoreCase = true)
    }

    private fun buildTable(): Map<String, Spec> {
        val table = mutableMapOf<String, Spec>()
        for (pkg in CHROMIUM_PACKAGES) {
            table[pkg] = Spec(CHROMIUM_URL_BAR_IDS.map { "$pkg:id/$it" })
        }
        for (pkg in FIREFOX_PACKAGES) {
            table[pkg] = Spec(
                ids = listOf("$pkg:id/mozac_browser_toolbar_url_view"),
                contentDescriptions = listOf(ADDRESS_BAR_DESCRIPTION),
            )
        }
        for (pkg in OPERA_PACKAGES) {
            table[pkg] = Spec(listOf("$pkg:id/url_field"))
        }
        for (pkg in SAMSUNG_PACKAGES) {
            table[pkg] = Spec(listOf("$pkg:id/location_bar_edit_text", "$pkg:id/url_bar"))
        }
        for (pkg in DUCKDUCKGO_PACKAGES) {
            table[pkg] = Spec(listOf("$pkg:id/omnibarTextInput"))
        }
        for (pkg in OEM_PACKAGES) {
            table[pkg] = Spec(listOf("$pkg:id/url_bar", "$pkg:id/url_edit_text"))
        }
        return table.toMap()
    }
}
