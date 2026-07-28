package app.drawbridge.dpc.vpn.dns

import app.drawbridge.policy.model.DnsPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MULLVAD = "tls://all.dns.mullvad.net"

class DnsFilterDecisionTest {

    private val blockEverything: (String) -> Boolean = { true }
    private val blockNothing: (String) -> Boolean = { false }

    @Test
    fun `blocked names are refused`() {
        assertEquals(
            DnsFilter.Decision.Block,
            decide(question("blocked.example"), DnsPolicy(), blockEverything),
        )
    }

    @Test
    fun `the encrypted upstream's own hostname is never blocked`() {
        // Every encrypted-DNS blocklist contains the public DoH providers, so
        // without the carve-out the filter would block its own upstream and the
        // device would resolve nothing at all.
        val decision = decide(
            question("all.dns.mullvad.net"),
            DnsPolicy(encryptedUpstream = MULLVAD),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Bootstrap, decision)
    }

    @Test
    fun `the encrypted upstream's own hostname is resolved over plain DNS`() {
        // Bootstrap rather than Forward: resolving the upstream over itself is a
        // deadlock.
        val decision = decide(
            question("all.dns.mullvad.net"),
            DnsPolicy(encryptedUpstream = MULLVAD),
            blockNothing,
        )
        assertEquals(DnsFilter.Decision.Bootstrap, decision)
    }

    @Test
    fun `a different encrypted-DNS provider's hostname is still blocked`() {
        // Only *our* upstream is exempt. Another app pointing at Cloudflare must
        // still be stopped.
        val decision = decide(
            question("cloudflare-dns.com"),
            DnsPolicy(encryptedUpstream = MULLVAD),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Block, decision)
    }

    @Test
    fun `a subdomain of the encrypted upstream is not exempt`() {
        val decision = decide(
            question("evil.all.dns.mullvad.net"),
            DnsPolicy(encryptedUpstream = MULLVAD),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Block, decision)
    }

    @Test
    fun `with no encrypted upstream configured nothing is exempt`() {
        val decision = decide(
            question("all.dns.mullvad.net"),
            DnsPolicy(encryptedUpstream = null),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Block, decision)
    }

    @Test
    fun `HTTPS record queries are answered empty so ECH is not negotiated`() {
        val decision = decide(
            question("example.com", DnsMessage.TYPE_HTTPS),
            DnsPolicy(),
            blockNothing,
        )
        assertEquals(DnsFilter.Decision.Empty, decision)
    }

    @Test
    fun `blocking wins over the HTTPS-record rule`() {
        val decision = decide(
            question("blocked.example", DnsMessage.TYPE_HTTPS),
            DnsPolicy(),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Block, decision)
    }

    @Test
    fun `HTTPS records pass through when stripping is disabled`() {
        val decision = decide(
            question("example.com", DnsMessage.TYPE_HTTPS),
            DnsPolicy(stripHttpsRecords = false),
            blockNothing,
        )
        assertEquals(DnsFilter.Decision.Forward, decision)
    }

    @Test
    fun `search domains are redirected to safe search`() {
        val decision = decide(question("www.google.com"), DnsPolicy(), blockNothing)
        assertEquals(DnsFilter.Decision.Redirect("forcesafesearch.google.com"), decision)
    }

    @Test
    fun `safe search only applies to address queries`() {
        val decision = decide(
            question("www.google.com", DnsMessage.TYPE_HTTPS),
            DnsPolicy(enforceSafeSearch = true),
            blockNothing,
        )
        // An HTTPS-record query for a search domain is answered empty, not
        // redirected — a redirect would have to carry A records it has none of.
        assertEquals(DnsFilter.Decision.Empty, decision)
    }

    @Test
    fun `safe search can be turned off`() {
        val decision = decide(
            question("www.google.com"),
            DnsPolicy(enforceSafeSearch = false),
            blockNothing,
        )
        assertEquals(DnsFilter.Decision.Forward, decision)
    }

    @Test
    fun `non-internet classes are passed straight through`() {
        val decision = decide(
            DnsMessage.Question("example.com", DnsMessage.TYPE_A, klass = 3, endOffset = 0),
            DnsPolicy(),
            blockEverything,
        )
        assertEquals(DnsFilter.Decision.Forward, decision)
    }

    @Test
    fun `a malformed encrypted upstream disables the carve-out rather than crashing`() {
        val decision = decide(
            question("all.dns.mullvad.net"),
            DnsPolicy(encryptedUpstream = "not a url"),
            blockNothing,
        )
        assertEquals(DnsFilter.Decision.Forward, decision)
    }

    @Test
    fun `extracts the host from an encrypted upstream`() {
        assertEquals("all.dns.mullvad.net", DnsFilter.encryptedHostOf(MULLVAD))
        assertEquals("dns.example", DnsFilter.encryptedHostOf("tls://DNS.Example:853"))
        assertEquals(null, DnsFilter.encryptedHostOf(null))
    }

    private fun decide(
        question: DnsMessage.Question,
        dns: DnsPolicy,
        isHostBlocked: (String) -> Boolean,
    ) = DnsFilter.decide(question, dns, isHostBlocked)

    private fun question(name: String, type: Int = DnsMessage.TYPE_A) =
        DnsMessage.Question(name, type, DnsMessage.CLASS_IN, endOffset = 0)
}
