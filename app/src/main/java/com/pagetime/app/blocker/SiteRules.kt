package com.pagetime.app.blocker

/**
 * Blocked sites: what the reader asked to stay off, and what a browser's
 * address bar actually said.
 *
 * DOMAIN OR DOMAIN + PATH, DECIDED BY WHAT WAS TYPED
 *
 * A rule is a host and an OPTIONAL path prefix. `bbc.co.uk` covers the whole
 * site, every page under it; `bbc.co.uk/news` covers that section and nothing
 * else. There is no third mode and no wildcard syntax to learn, because the
 * thing being typed is a web address and a web address already says this much.
 *
 * SUBDOMAINS COUNT
 *
 * `bbc.co.uk` also covers `www.bbc.co.uk` and `news.bbc.co.uk`. Both hosts are
 * stripped of a leading `www.` before anything is compared, so a rule written
 * with or without it behaves identically, and the rest is a dot-boundary
 * suffix test rather than `endsWith`. That distinction is the whole of the
 * correctness here: `evilbbc.co.uk` ends with `bbc.co.uk` as a string and is
 * not the same site, and blocking it by accident would be a bug the reader
 * could not see or explain.
 *
 * PATHS MATCH ON SEGMENT BOUNDARIES TOO
 *
 * `bbc.co.uk/news` must cover `/news` and `/news/world` but not `/newspaper`.
 * So the test is equality or `prefix + "/"`, never a bare prefix.
 *
 * EVERYTHING HERE IS A FUNCTION OF ITS ARGUMENTS, and there is no Android in
 * the file, for the same reason [BlockEnforcementPolicy] has none: the address
 * bar can be read on a device and this cannot be, so the part that can be
 * tested is kept apart from the part that cannot.
 */
object SiteRules {

    /** A site the reader has asked to stay off, as stored. */
    data class Rule(
        /** The host, lowercased and without a leading `www.`. */
        val host: String,
        /** `/news` for a section, null for the whole site. No trailing slash. */
        val pathPrefix: String?,
    ) {
        /**
         * The rule as one string, and the key it is stored under.
         *
         * Doubles as the identifier a block is logged against, which is why the
         * blocking stats can name a site by the same text the reader typed
         * instead of by a package name that does not exist.
         */
        val id: String
            get() = if (pathPrefix.isNullOrEmpty()) host else host + pathPrefix
    }

    /** A web address, reduced to the two parts a rule can talk about. */
    data class Url(
        val host: String,
        /** Always starts with `/`, or is empty at the root. Lowercased. */
        val path: String,
    )

    /** Hosts longer than the DNS limit are a typo, not a site. */
    private const val MAX_HOST_LENGTH = 253

    private const val SCHEME_HTTP = "http://"
    private const val SCHEME_HTTPS = "https://"

    /**
     * Schemes that are not the web at all.
     *
     * An address bar full of `chrome://flags` or `about:blank` must never be
     * mistaken for a host: `parseUrl` would otherwise read `chrome` as one.
     */
    private val NON_WEB_SCHEMES = listOf(
        "about:", "chrome:", "chrome-native:", "data:", "javascript:", "file:",
        "blob:", "content:", "view-source:", "intent:",
    )

    /**
     * Turns what the reader typed into a rule, or returns null if it is not a
     * web address.
     *
     * Null is the answer for a bare word on purpose. "news" is a search, not a
     * site, and a rule that blocked every host containing it would be a
     * surprise the reader could not debug from the screen that created it.
     * Anything without a dot in the host is therefore refused, and the screen
     * says so.
     */
    fun parse(input: String): Rule? {
        var rest = input.trim().lowercase()
        if (rest.isEmpty()) return null

        rest = rest.removePrefix(SCHEME_HTTP).removePrefix(SCHEME_HTTPS)
        rest = rest.substringBefore('#').substringBefore('?')
        // A pasted address can carry a trailing slash, which is not the path
        // separator of anything.
        val hostEnd = rest.indexOf('/')
        val rawHost = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
        val rawPath = if (hostEnd < 0) "" else rest.substring(hostEnd)

        val host = normaliseHost(rawHost) ?: return null
        return Rule(host, normalisePath(rawPath))
    }

    /**
     * Reduces a browser's address bar to a host and a path.
     *
     * Only the host and path take part in matching. Query strings and fragments
     * are dropped, so `bbc.co.uk/news?a=1` is the same URL as `bbc.co.uk/news`.
     * A rule that had to name a query parameter would be a rule nobody could
     * write from a phone keyboard.
     */
    fun parseUrl(raw: String): Url? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) return null
        if (NON_WEB_SCHEMES.any { trimmed.startsWith(it) }) return null

        var rest = when {
            trimmed.startsWith(SCHEME_HTTPS) -> trimmed.removePrefix(SCHEME_HTTPS)
            trimmed.startsWith(SCHEME_HTTP) -> trimmed.removePrefix(SCHEME_HTTP)
            // Any other explicit scheme is not something a site rule describes.
            trimmed.contains("://") -> return null
            else -> trimmed
        }
        rest = rest.substringBefore('#')

        // A query can arrive on the root path, so the host ends at whichever of
        // the two separators comes first.
        val cut = listOf(rest.indexOf('/'), rest.indexOf('?'))
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        val host = normaliseHost(rest.substring(0, cut)) ?: return null

        // The query has to come off the PATH too, not just off the host. A rule
        // is a host and a path and nothing else, and leaving the query attached
        // would make `bbc.co.uk/news?x=1` fail to match the rule
        // `bbc.co.uk/news` — the failure being a blocked page that quietly loads,
        // which is exactly the kind of bug nobody reports as a bug.
        val pathStart = rest.indexOf('/')
        val rawPath = if (pathStart < 0) "" else rest.substring(pathStart).substringBefore('?')
        return Url(host, if (rawPath.isBlank()) "" else normaliseUrlPath(rawPath))
    }

    /**
     * Whether [rule] covers [url].
     *
     * The dot boundary and the segment boundary are the two places this can be
     * subtly wrong, so both are spelled out rather than left to `endsWith` and
     * `startsWith`.
     */
    fun matches(rule: Rule, url: Url): Boolean {
        if (!hostCovers(rule.host, url.host)) return false
        val prefix = rule.pathPrefix ?: return true
        return url.path == prefix || url.path.startsWith("$prefix/")
    }

    /** The first rule covering [url], or null. Rules are checked in order. */
    fun firstMatch(rules: List<Rule>, url: Url): Rule? = rules.firstOrNull { matches(it, url) }

    /** Convenience for callers holding the address bar's text. */
    fun match(rawUrl: String, rules: List<Rule>): Rule? {
        val url = parseUrl(rawUrl) ?: return null
        return firstMatch(rules, url)
    }

    /** Whether [host] is [ruleHost] or a subdomain of it. */
    private fun hostCovers(ruleHost: String, host: String): Boolean =
        host == ruleHost || host.endsWith(".$ruleHost")

    /**
     * Lowers, strips `www.`, and refuses anything that is not a plausible host.
     *
     * Also the gate that turns a search query into null: browsers happily put
     * "how to cook rice" in the same view as a URL, and it must not become a
     * host named `how to cook rice`.
     */
    private fun normaliseHost(raw: String): String? {
        var host = raw.trim().lowercase()
        // Credentials and a port are address-bar furniture, not part of a site.
        host = host.substringAfterLast('@').substringBefore(':')
        host = host.removePrefix("www.").trimEnd('.')
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return null
        if (!host.contains('.')) return null
        if (host.startsWith('.')) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return host
    }

    /** A rule's path: `/news`, or null when the rule is the whole site. */
    private fun normalisePath(raw: String): String? {
        if (raw.isBlank() || raw == "/") return null
        var path = raw.trim().lowercase()
        if (!path.startsWith("/")) path = "/$path"
        path = path.trimEnd('/')
        return if (path.isEmpty() || path == "/") null else path
    }

    /** A URL's path, where the root is an empty string rather than a slash. */
    private fun normaliseUrlPath(raw: String): String {
        if (raw.isBlank() || raw == "/") return ""
        return raw.trim().lowercase().trimEnd('/')
    }
}
