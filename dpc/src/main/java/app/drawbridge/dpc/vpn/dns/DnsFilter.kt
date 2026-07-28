package app.drawbridge.dpc.vpn.dns

import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.model.DnsPolicy
import java.net.URI

/**
 * Turns a DNS question into a decision, using the same shared policy herald
 * consults for page loads.
 */
class DnsFilter(private val policy: PolicyManager) {

    sealed interface Decision {
        /** Send the query upstream — encrypted when an upstream is configured. */
        data object Forward : Decision

        /**
         * Send the query to a plain-DNS upstream, never encrypted, and never
         * block it. Used only for the encrypted upstream's own hostname.
         */
        data object Bootstrap : Decision

        /** Answer NXDOMAIN: the name is on the blocklist. */
        data object Block : Decision

        /**
         * Answer NOERROR with no records — "this name exists, but has nothing of
         * the type you asked for".
         */
        data object Empty : Decision

        /** Answer with [target]'s addresses under the queried name. */
        data class Redirect(val target: String) : Decision
    }

    fun decide(question: DnsMessage.Question): Decision =
        decide(question, policy.policy.value.dns, policy::isHostBlocked)

    companion object {

        /**
         * The decision itself, expressed against plain values rather than the
         * policy singleton so that every branch can be exercised in a unit test.
         * This is the most security-relevant logic in the app; it should not be
         * reachable only through a running Android service.
         */
        internal fun decide(
            question: DnsMessage.Question,
            dns: DnsPolicy,
            isHostBlocked: (String) -> Boolean,
        ): Decision {
            if (question.klass != DnsMessage.CLASS_IN) return Decision.Forward

            // The encrypted upstream's own hostname has to be handled before
            // anything else, for two independent reasons:
            //
            //  1. It is on the blocklist. Every encrypted-DNS list includes the
            //     public providers — that is what those lists are for — so
            //     without this the filter would block its own upstream.
            //  2. Resolving it over the encrypted channel would require
            //     resolving it first. That is a deadlock, so it goes to a
            //     plain-DNS upstream instead.
            encryptedHostOf(dns.encryptedUpstream)?.let { upstreamHost ->
                if (question.name == upstreamHost) return Decision.Bootstrap
            }

            if (isHostBlocked(question.name)) return Decision.Block

            // HTTPS/SVCB records carry ECH configuration. Answering them empty
            // keeps ECH from being negotiated, which preserves the option of
            // SNI-level filtering later; it costs nothing today because clients
            // fall back to A/AAAA. Refusing the query is used rather than
            // stripping the record from a forwarded answer, which would mean
            // rewriting a message full of compression pointers.
            if (dns.stripHttpsRecords &&
                (question.type == DnsMessage.TYPE_HTTPS || question.type == DnsMessage.TYPE_SVCB)
            ) {
                return Decision.Empty
            }

            if (dns.enforceSafeSearch && isAddressQuery(question.type)) {
                safeSearchTargetFor(question.name)?.let { return Decision.Redirect(it) }
            }

            return Decision.Forward
        }

        private fun isAddressQuery(type: Int): Boolean =
            type == DnsMessage.TYPE_A || type == DnsMessage.TYPE_AAAA

        @Volatile
        private var cachedUpstream: String? = null

        @Volatile
        private var cachedUpstreamHost: String? = null

        /** Memoised: this runs on every query, and re-parsing a URI each time is waste. */
        internal fun encryptedHostOf(upstream: String?): String? {
            if (upstream == null) return null
            if (upstream != cachedUpstream) {
                cachedUpstreamHost = runCatching { URI(upstream).host?.lowercase() }.getOrNull()
                cachedUpstream = upstream
            }
            return cachedUpstreamHost
        }

        /**
         * Hostnames each provider publishes for enforced safe search. Resolving
         * the ordinary hostname to one of these is the supported, documented way
         * to force it — the same mechanism school and enterprise networks use.
         */
        private const val GOOGLE_SAFE = "forcesafesearch.google.com"
        private const val YOUTUBE_MODERATE = "restrictmoderate.youtube.com"
        private const val BING_SAFE = "strict.bing.com"
        private const val DUCKDUCKGO_SAFE = "safe.duckduckgo.com"
        private const val PIXABAY_SAFE = "safesearch.pixabay.com"

        private val EXACT_REDIRECTS: Map<String, String> = buildMap {
            listOf(
                "www.youtube.com",
                "m.youtube.com",
                "youtube.com",
                "youtubei.googleapis.com",
                "youtube.googleapis.com",
                "www.youtube-nocookie.com",
            ).forEach { put(it, YOUTUBE_MODERATE) }

            listOf("www.bing.com", "bing.com").forEach { put(it, BING_SAFE) }
            listOf("duckduckgo.com", "www.duckduckgo.com").forEach { put(it, DUCKDUCKGO_SAFE) }
            listOf("pixabay.com", "www.pixabay.com").forEach { put(it, PIXABAY_SAFE) }
        }

        /**
         * Google runs a search domain per country (google.be, google.co.uk, …)
         * and they all honour the same safe-search hostname, so match the shape
         * rather than enumerating them.
         */
        internal fun safeSearchTargetFor(name: String): String? {
            EXACT_REDIRECTS[name]?.let { return it }

            val withoutWww = name.removePrefix("www.")
            EXACT_REDIRECTS[withoutWww]?.let { return it }

            if (withoutWww == "google.com" || withoutWww.startsWith("google.")) {
                // google.<tld> and google.co.<tld>, but not googleapis.com or
                // googleusercontent.com, which are not search front ends.
                val suffix = withoutWww.removePrefix("google.")
                if (suffix.isNotEmpty() && suffix.all { it.isLetter() || it == '.' }) {
                    return GOOGLE_SAFE
                }
            }
            return null
        }
    }
}
