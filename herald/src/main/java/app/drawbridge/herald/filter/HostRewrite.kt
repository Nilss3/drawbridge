package app.drawbridge.herald.filter

/**
 * Sends a load for one host to another, keeping everything after the host.
 *
 * Driven by `browser.host_rewrites` in the policy. The case it exists for is
 * DuckDuckGo while the chatbots switch is off: `noai.duckduckgo.com` is the same
 * engine with its AI answers and its chat link switched off, so a search typed
 * into herald, a link to a results page and a bookmark all land there instead.
 *
 * **Exact hosts only**, with `www.` treated as the bare host. A suffix match
 * would rewrite `noai.duckduckgo.com` itself — the target is a subdomain of the
 * source — and loop. Matching exactly is what makes the rewritten URL come back
 * through here and be left alone.
 *
 * Raw string handling, like [Shorts] and
 * [app.drawbridge.herald.search.SafeSearch]: nothing is decoded, and the test
 * source set runs it without Robolectric.
 */
object HostRewrite {

    /** The URL with its host replaced, or null to leave it alone. */
    fun redirected(url: String, rewrites: Map<String, String>): String? {
        if (rewrites.isEmpty()) return null
        val scheme = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(0, 8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(0, 7)
            else -> return null
        }
        val rest = url.substring(scheme.length)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        // userinfo is not something a search URL carries; leave such URLs alone
        // rather than reason about where the host starts.
        if ('@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        val port = authority.substring(authority.substringBefore(':').length)
        val bare = host.removePrefix("www.")

        val target = rewrites.entries.firstOrNull { (from, _) ->
            from.lowercase().removePrefix("www.") == bare
        }?.value ?: return null
        if (target.equals(host, ignoreCase = true)) return null

        return scheme + target + port + rest.substring(authorityEnd)
    }
}
