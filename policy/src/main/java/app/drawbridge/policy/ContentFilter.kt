package app.drawbridge.policy

import app.drawbridge.policy.blocklist.CompositeDomainSet
import app.drawbridge.policy.blocklist.DomainSet
import app.drawbridge.policy.model.BrowserPolicy
import java.io.Closeable
import java.net.URI

/**
 * The single place a "should this be blocked?" question is answered, used by
 * both enforcement layers: the DNS filter asks about bare hostnames, herald asks
 * about full URLs.
 *
 * Allow rules win over block rules, so a policy can carve an exception out of a
 * bulk blocklist without editing it.
 */
class ContentFilter(
    private val blockedDomains: DomainSet,
    private val allowedDomains: DomainSet,
    private val blockedUrlPatterns: List<Regex> = emptyList(),
) : Closeable {

    /** True if DNS resolution for [host] should be refused. */
    fun isHostBlocked(host: String): Boolean {
        val normalised = DomainSet.normalise(host)
        if (normalised.isEmpty()) return false
        if (allowedDomains.matches(normalised)) return false
        return blockedDomains.matches(normalised)
    }

    /**
     * True if [url] should not load. Checks the host first, then the path-level
     * patterns — the finer-grained rules the DNS layer cannot express.
     */
    fun isUrlBlocked(url: String): Boolean {
        val host = hostOf(url)
        if (host != null) {
            if (allowedDomains.matches(host)) return false
            if (blockedDomains.matches(host)) return true
        }
        return blockedUrlPatterns.any { it.containsMatchIn(url) }
    }

    override fun close() {
        (blockedDomains as? Closeable)?.close()
        (allowedDomains as? Closeable)?.close()
    }

    companion object {
        val PERMISSIVE = ContentFilter(DomainSet.EMPTY, DomainSet.EMPTY)

        /** Extracts the host from a URL without throwing on the malformed ones. */
        fun hostOf(url: String): String? {
            runCatching { URI(url).host }.getOrNull()?.let { return it.cleanHost() }

            // URI is stricter about characters than browsers are, so fall back to
            // slicing the authority out by hand.
            val authority = when {
                url.contains("://") -> url.substringAfter("://")
                // Scheme-relative input such as "example.com/a" — no scheme, no host
                // to confuse with a path.
                !url.substringBefore('/').contains(':') -> url
                // Opaque URIs like "about:blank" or "data:..." have no host at all.
                else -> return null
            }

            return authority
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
                .cleanHost()
        }

        private fun String.cleanHost(): String? =
            trim().trimStart('[').trimEnd(']').lowercase().takeIf { it.isNotEmpty() }

        /**
         * Assembles a filter from a compiled blocklist plus the domains and URL
         * patterns inlined in the policy document.
         */
        fun create(
            compiledBlocklist: DomainSet?,
            extraBlockedDomains: Collection<String>,
            allowedDomains: Collection<String>,
            browser: BrowserPolicy?,
        ): ContentFilter {
            val blockedMembers = buildList {
                compiledBlocklist?.let(::add)
                val inline = extraBlockedDomains + (browser?.blockedHosts ?: emptyList())
                if (inline.isNotEmpty()) add(DomainSet.of(inline))
            }
            val blocked = when (blockedMembers.size) {
                0 -> DomainSet.EMPTY
                1 -> blockedMembers.single()
                else -> CompositeDomainSet(blockedMembers)
            }
            val patterns = (browser?.blockedUrlPatterns ?: emptyList()).mapNotNull { pattern ->
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            }
            return ContentFilter(blocked, DomainSet.of(allowedDomains), patterns)
        }
    }
}
