package app.drawbridge.herald.search

/**
 * Forces safe search on the engines herald offers, on every load.
 *
 * **Why this exists at all.** drawbridge rewrites Google, Bing and DuckDuckGo at
 * the DNS layer, which is the strongest form of this there is: the hostname
 * resolves where the filter says whatever the browser asks for, and nothing
 * typed into an address bar can undo it. But it only reaches engines that
 * publish a safe hostname, and the engine list lives here rather than in the
 * policy's DNS rules — so every other engine herald offered was one address bar
 * away from unfiltered results. That was the loophole.
 *
 * **Why a redirect rather than a search-engine URL.** Baking `&safesearch=2`
 * into the engine's configured URL only covers searches started from the search
 * bar. Typing the query URL by hand, editing the parameter out, or restoring a
 * saved session all bypass it. [app.drawbridge.herald.filter.HeraldRequestInterceptor]
 * sees app-initiated loads too, so putting the rule here means the parameter
 * goes back on every time.
 *
 * **What this does not cover, and it is worth knowing.** These engines are
 * single-page apps: a *second* query typed into the engine's own box may not
 * produce a top-level navigation, so there is nothing to intercept. The first
 * load is enforced; a subsequent in-page search is not. That is why an engine
 * whose safe search is only a URL parameter is second-class here, and why the
 * three that drawbridge rewrites at the DNS layer are the ones the policy
 * recommends.
 *
 * **Engines that were removed rather than enforced.** Brave Search can only be
 * forced by writing a `safesearch` cookie — the vendor answer for enterprise
 * filters rewrites the Cookie header and needs TLS interception, which this
 * project will not do. Startpage searches by POST on purpose, so there is no
 * parameter to set. Qwant documents one that reference implementations note is
 * not actually heeded. None of the three could be made honest, so none of them
 * are offered; see [SearchEngineCatalogue].
 *
 * **Kagi carries no rule and is still safe**: logged out it filters explicit
 * results with no setting to turn that off, and turning it off needs a paid
 * account to sign into. Nothing to force, so nothing is forced.
 */
object SafeSearch {

    /**
     * The parameter each engine wants, keyed by the host that honours it.
     *
     * Google, Bing and DuckDuckGo are here despite the DNS rewrite covering
     * them, because **herald also ships without drawbridge**. Standalone there
     * is no filter rewriting anything, and the same browser should not be safe
     * only when something else is watching.
     */
    private val RULES = listOf(
        Rule(::isGoogleSearchHost, mapOf("safe" to "active")),
        Rule(hostSuffix("bing.com"), mapOf("adlt" to "strict")),
        Rule(hostSuffix("duckduckgo.com"), mapOf("kp" to "1")),
        Rule(hostSuffix("ecosia.org"), mapOf("safesearch" to "2")),
    )

    private data class Rule(val matches: (String) -> Boolean, val params: Map<String, String>)

    private fun hostSuffix(suffix: String): (String) -> Boolean =
        { host -> host == suffix || host.endsWith(".$suffix") }

    /**
     * Google runs a search front end per country — google.be, google.co.uk and
     * dozens more — and they all honour `safe=active`. Matching only google.com
     * would have left every one of them unforced, which is the same shape of
     * gap this whole file exists to close.
     *
     * The rule mirrors `DnsFilter.safeSearchTargetFor` deliberately, including
     * its exclusions: googleapis.com and googleusercontent.com are not search
     * front ends and adding a parameter to them would be noise. If one of the
     * two is ever changed, change the other.
     */
    private fun isGoogleSearchHost(host: String): Boolean {
        val withoutWww = host.removePrefix("www.").substringAfter("images.")
        if (!withoutWww.startsWith("google.")) return false
        val suffix = withoutWww.removePrefix("google.")
        return suffix.isNotEmpty() && suffix.all { it.isLetter() || it == '.' }
    }

    /**
     * The URL this one should have been, or null when it is already right —
     * which is also what stops a redirect loop, since the rewritten URL comes
     * straight back through here and has to be recognised as satisfied.
     *
     * Deliberately built on the raw query string rather than `android.net.Uri`.
     * Two reasons, and the second is the important one: it keeps this plain JVM
     * code that herald's test source set can exercise without pulling in
     * Robolectric, and it never decodes anything. A search term is arbitrary
     * text — spaces, plus signs, accents, percent escapes — and decoding it only
     * to re-encode it is a chance to hand the engine a different query than the
     * one that was typed. Every original byte is passed through untouched;
     * only the parameters being overridden are rewritten, and their values are
     * ASCII constants.
     */
    fun enforced(url: String): String? {
        val host = hostOf(url) ?: return null
        val rule = RULES.firstOrNull { it.matches(host) } ?: return null

        val fragmentAt = url.indexOf('#')
        val withoutFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""

        val queryAt = withoutFragment.indexOf('?')
        if (queryAt < 0) return null
        val path = withoutFragment.substring(0, queryAt)
        val pairs = withoutFragment.substring(queryAt + 1)
            .split('&')
            .filter { it.isNotEmpty() }

        // Only searches. Every engine with a rule carries the query in `q`, so
        // its absence means a front page, a settings screen or an article —
        // none of which take a safe-search parameter, and all of which this
        // would otherwise redirect in a loop.
        if (pairs.none { it.key() == "q" && it.value().isNotEmpty() }) return null

        val satisfied = rule.params.all { (key, value) ->
            pairs.any { it.key() == key && it.value() == value }
        }
        if (satisfied) return null

        val kept = pairs.filterNot { it.key() in rule.params }
        val forced = rule.params.map { (key, value) -> "$key=$value" }
        return path + "?" + (kept + forced).joinToString("&") + fragment
    }

    /**
     * The host, without parsing the rest. `java.net.URI` throws on URLs that a
     * browser handles perfectly well, and everything needed here is before the
     * first slash.
     */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .ifEmpty { null }
    }

    private fun String.key(): String = substringBefore('=')

    private fun String.value(): String = substringAfter('=', missingDelimiterValue = "")
}
