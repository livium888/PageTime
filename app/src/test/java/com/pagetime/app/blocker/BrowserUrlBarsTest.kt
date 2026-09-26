package com.pagetime.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which node in a browser window is the address bar.
 *
 * This is the half of blocked sites that cannot be verified by reading the
 * code, because it depends on what real browsers actually call their views. So
 * the tests here are mostly about the two ways a wrong answer costs something:
 * picking a node that is not the bar blocks a page the reader is not on, and
 * failing to pick the bar means a rule that silently never fires.
 */
class BrowserUrlBarsTest {

    private fun node(
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String? = null,
    ) = BrowserUrlBars.Node(viewId, text, contentDescription, className)

    // --- The table ---

    /**
     * The families the table exists to cover. Each one is a different naming
     * convention, so a table that lost any of these rows would still look fine
     * and would fail for a reader on that browser.
     */
    @Test
    fun `the table covers every browser family`() {
        for (pkg in listOf(
            "com.android.chrome",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.opera.browser",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
        )) {
            assertNotNull("no table entry for $pkg", BrowserUrlBars.specFor(pkg))
            assertTrue(BrowserUrlBars.isKnownBrowser(pkg))
        }
    }

    /**
     * Every id in the table has to be fully qualified. An accessibility view id
     * is `package:id/name`, and one written without the package would simply
     * never be found — the failure mode being a rule that does not work rather
     * than an error anyone would see.
     */
    @Test
    fun `every table id is qualified with its own package`() {
        for ((pkg, spec) in BrowserUrlBars.BROWSERS) {
            assertTrue("$pkg has no ids", spec.ids.isNotEmpty())
            for (id in spec.ids) {
                assertTrue("$id is not qualified for $pkg", id.startsWith("$pkg:id/"))
            }
        }
    }

    @Test
    fun `the table's own ids are asked for before the conventions`() {
        val ids = BrowserUrlBars.candidateIds("com.android.chrome")
        assertEquals("com.android.chrome:id/url_bar", ids.first())
        assertTrue(ids.contains("com.android.chrome:id/url_field"))
    }

    @Test
    fun `a browser the table has never seen is still looked for by convention`() {
        val ids = BrowserUrlBars.candidateIds("com.example.newbrowser")
        assertTrue(ids.contains("com.example.newbrowser:id/url_bar"))
        assertFalse(BrowserUrlBars.isKnownBrowser("com.example.newbrowser"))
    }

    // --- Navigation commits ---

    @Test
    fun `a Go control is a navigation commit`() {
        assertTrue(
            BrowserUrlBars.isNavigationCommitAction(
                node(viewId = "com.android.chrome:id/go_button")
            )
        )
        assertTrue(
            BrowserUrlBars.isNavigationCommitAction(
                node(contentDescription = "Go")
            )
        )
    }

    @Test
    fun `editing the address bar or a page search is not a navigation commit`() {
        assertFalse(
            BrowserUrlBars.isNavigationCommitAction(
                node(viewId = "com.android.chrome:id/url_bar")
            )
        )
        assertFalse(
            BrowserUrlBars.isNavigationCommitAction(
                node(text = "Search", className = "android.widget.Button")
            )
        )
        assertFalse(
            BrowserUrlBars.isNavigationCommitAction(
                node(contentDescription = "Search the web")
            )
        )
    }

    @Test
    fun `keyboard navigation labels are recognized separately`() {
        assertTrue(
            BrowserUrlBars.isInputMethodNavigationCommitAction(
                node(contentDescription = "Go")
            )
        )
        assertTrue(
            BrowserUrlBars.isInputMethodNavigationCommitAction(
                node(contentDescription = "Search")
            )
        )
        assertFalse(
            BrowserUrlBars.isInputMethodNavigationCommitAction(
                node(contentDescription = "Backspace")
            )
        )
    }

    // --- Recognising a bar in a browser nobody has named ---

    @Test
    fun `the conventional view ids are address bars`() {
        assertTrue(BrowserUrlBars.looksLikeAddressBar(node(viewId = "com.example:id/url_bar")))
        assertTrue(BrowserUrlBars.looksLikeAddressBar(node(viewId = "com.example:id/url_field")))
        assertTrue(
            BrowserUrlBars.looksLikeAddressBar(
                node(viewId = "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
            )
        )
    }

    @Test
    fun `a node that merely starts with url_bar is not one`() {
        assertFalse(
            BrowserUrlBars.looksLikeAddressBar(node(viewId = "com.example:id/url_bar_background"))
        )
        assertFalse(BrowserUrlBars.looksLikeAddressBar(node(viewId = "com.example:id/toolbar")))
        assertFalse(BrowserUrlBars.looksLikeAddressBar(node(contentDescription = "Reload")))
    }

    @Test
    fun `the known content descriptions count`() {
        assertTrue(
            BrowserUrlBars.looksLikeAddressBar(
                node(contentDescription = "ADDRESSBAR_URL_BOX")
            )
        )
        assertTrue(
            BrowserUrlBars.looksLikeAddressBar(
                node(contentDescription = "Search or enter address")
            )
        )
    }

    // --- Choosing the text ---

    @Test
    fun `the table's own node wins over a nicer-looking stranger`() {
        val url = BrowserUrlBars.pickUrl(
            listOf(
                node(viewId = "com.android.chrome:id/url_bar", text = "https://bbc.co.uk/news"),
                // A tab or a suggestion that also looks like a bar must not
                // outrank the real one.
                node(viewId = "com.android.chrome:id/suggestion_url_bar", text = "example.com"),
            ),
            packageName = "com.android.chrome",
        )
        assertEquals("https://bbc.co.uk/news", url)
    }

    @Test
    fun `the firefox family is found by its content description`() {
        val url = BrowserUrlBars.pickUrl(
            listOf(
                node(
                    viewId = "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                    contentDescription = "ADDRESSBAR_URL_BOX",
                    text = "bbc.co.uk",
                ),
            ),
            packageName = "org.mozilla.firefox",
        )
        assertEquals("bbc.co.uk", url)
    }

    @Test
    fun `a browser not in the table is read through the convention`() {
        val url = BrowserUrlBars.pickUrl(
            listOf(node(viewId = "com.example.newbrowser:id/url_bar", text = "bbc.co.uk/news")),
            packageName = "com.example.newbrowser",
        )
        assertEquals("bbc.co.uk/news", url)
    }

    /**
     * An empty address bar is what a new tab looks like, and returning it would
     * mean a rule being matched against nothing.
     */
    @Test
    fun `a blank bar is not an answer`() {
        assertNull(
            BrowserUrlBars.pickUrl(
                listOf(node(viewId = "com.android.chrome:id/url_bar", text = "")),
                packageName = "com.android.chrome",
            )
        )
        assertNull(
            BrowserUrlBars.pickUrl(
                listOf(node(viewId = "com.android.chrome:id/url_bar")),
                packageName = "com.android.chrome",
            )
        )
    }

    /**
     * The last resort. Both halves are required: a text field AND contents that
     * read like a web address. Without the first, a bookmark or a tab in the
     * switcher would be taken for the current page — and the reader would be
     * bounced out of a page they are not on.
     */
    @Test
    fun `a text field holding an address is the last resort`() {
        val url = BrowserUrlBars.pickUrl(
            listOf(node(text = "bbc.co.uk", className = "android.widget.EditText")),
            packageName = "com.example.newbrowser",
        )
        assertEquals("bbc.co.uk", url)
    }

    @Test
    fun `a label holding an address is not a bar`() {
        assertNull(
            BrowserUrlBars.pickUrl(
                listOf(node(text = "bbc.co.uk", className = "android.widget.TextView")),
                packageName = "com.example.newbrowser",
            )
        )
    }

    @Test
    fun `a text field holding something that is not an address is not a bar`() {
        assertNull(
            BrowserUrlBars.pickUrl(
                listOf(node(text = "how to cook rice", className = "android.widget.EditText")),
                packageName = "com.example.newbrowser",
            )
        )
    }

    @Test
    fun `a window with no bar at all yields nothing`() {
        assertNull(
            BrowserUrlBars.pickUrl(
                listOf(
                    node(viewId = "com.android.chrome:id/toolbar", className = "android.widget.FrameLayout"),
                    node(text = "Reload", contentDescription = "Reload"),
                ),
                packageName = "com.android.chrome",
            )
        )
    }
}
