package com.pagetime.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every destination the app navigates to has to exist, and every tab in the
 * bottom bar has to lead somewhere.
 *
 * The concept map screen was written in full and then never given a route:
 * every "open concepts" callback navigated to the Lumen box instead, while the
 * maps went on being generated — and paid for in Gemini calls — with nothing
 * able to show them. A Review tab sat in the bottom bar for a card type
 * nothing could create. Neither was a broken build or a failing test; both were
 * things the compiler is happy with and a reader is not.
 *
 * This reads the navigation graph as text because that is the only place the
 * two halves are written down. It is a cheap check against the one mistake
 * this app keeps making: something visible with nothing behind it.
 */
class NavigationGraphTest {

    private val source: String by lazy {
        val path = "src/main/java/com/pagetime/app/ui/PageTimeAppUi.kt"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        assertTrue("Cannot find $path from ${File(".").absolutePath}", file.exists())
        file.readText()
    }

    /** Route patterns declared with composable("..."). */
    private val declaredRoutes: List<String> by lazy {
        Regex("""composable\("([^"]+)"\)""").findAll(source).map { it.groupValues[1] }.toList()
    }

    /** Destinations passed to navigate("..."), with interpolations blanked out. */
    private val navigationTargets: List<String> by lazy {
        Regex("navigate\\(\"([^\"]+)\"").findAll(source)
            .map { it.groupValues[1] }
            .map { target ->
                target
                    .replace(Regex("""\$\{[^}]*}"""), "ARG")
                    .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "ARG")
            }
            .toList()
    }

    /** A declared route as a matcher: {placeholders} accept one path segment. */
    private fun routeMatcher(route: String): Regex {
        val path = route.substringBefore('?')
        val pattern = path.split('/').joinToString("/") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)
        }
        // The trailing anchor is escaped rather than written bare: an
        // end-of-text anchor that quietly became a literal dollar has already
        // cost this codebase a release.
        return Regex("^$pattern\$")
    }

    @Test
    fun `the navigation graph declares at least the screens we know about`() {
        assertTrue("No routes found — the parser is broken, not the graph", declaredRoutes.size >= 8)
        assertTrue("No navigate() calls found", navigationTargets.isNotEmpty())
    }

    @Test
    fun `every destination navigated to is a declared route`() {
        val matchers = declaredRoutes.map(::routeMatcher)
        navigationTargets.forEach { target ->
            val path = target.substringBefore('?')
            assertTrue(
                "navigate(\"$target\") has no matching composable(). Declared: $declaredRoutes",
                matchers.any { it.matches(path) }
            )
        }
    }

    @Test
    fun `every bottom tab leads to a declared route`() {
        val tabs = Regex("BottomTab\\(\"([^\"]+)\"").findAll(source)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("No bottom tabs found", tabs.isNotEmpty())
        val matchers = declaredRoutes.map(::routeMatcher)
        tabs.forEach { route ->
            assertTrue(
                "The \"$route\" tab has no composable(). Declared: $declaredRoutes",
                matchers.any { it.matches(route) }
            )
        }
    }

    @Test
    fun `the concept map is reachable`() {
        // The specific regression: the screen existed, the maps were generated,
        // and nothing could open them.
        assertTrue(
            "No route opens the concept map",
            declaredRoutes.any { it.startsWith("concepts") }
        )
        assertTrue(
            "Nothing navigates to the concept map",
            navigationTargets.any { it.startsWith("concepts") }
        )
    }
}
