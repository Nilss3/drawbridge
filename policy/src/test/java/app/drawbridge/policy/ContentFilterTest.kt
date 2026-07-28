package app.drawbridge.policy

import app.drawbridge.policy.blocklist.DomainSet
import app.drawbridge.policy.model.BrowserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {

    @Test
    fun `blocks a listed host and its subdomains`() {
        val filter = filterOf(blocked = listOf("blocked.example"))
        assertTrue(filter.isHostBlocked("blocked.example"))
        assertTrue(filter.isHostBlocked("cdn.blocked.example"))
        assertFalse(filter.isHostBlocked("allowed.example"))
    }

    @Test
    fun `allow rules win over block rules`() {
        val filter = filterOf(
            blocked = listOf("example.com"),
            allowed = listOf("school.example.com"),
        )
        assertTrue(filter.isHostBlocked("www.example.com"))
        assertFalse(filter.isHostBlocked("school.example.com"))
        assertFalse(filter.isHostBlocked("portal.school.example.com"))
    }

    @Test
    fun `blocks URLs whose host is blocked`() {
        val filter = filterOf(blocked = listOf("blocked.example"))
        assertTrue(filter.isUrlBlocked("https://blocked.example/some/page?q=1"))
        assertFalse(filter.isUrlBlocked("https://fine.example/some/page"))
    }

    @Test
    fun `blocks URLs matching a path-level pattern`() {
        val filter = ContentFilter.create(
            compiledBlocklist = null,
            extraBlockedDomains = emptyList(),
            allowedDomains = emptyList(),
            browser = BrowserPolicy(blockedUrlPatterns = listOf("""reddit\.com/r/(gonewild|nsfw)""")),
        )
        assertTrue(filter.isUrlBlocked("https://www.reddit.com/r/nsfw/top"))
        assertFalse(filter.isUrlBlocked("https://www.reddit.com/r/kotlin"))
    }

    @Test
    fun `an allow rule also short-circuits URL pattern matching`() {
        val filter = ContentFilter.create(
            compiledBlocklist = null,
            extraBlockedDomains = emptyList(),
            allowedDomains = listOf("intranet.example"),
            browser = BrowserPolicy(blockedUrlPatterns = listOf("""example/secret""")),
        )
        assertFalse(filter.isUrlBlocked("https://intranet.example/secret"))
    }

    @Test
    fun `browser-only blocked hosts are honoured`() {
        val filter = ContentFilter.create(
            compiledBlocklist = null,
            extraBlockedDomains = emptyList(),
            allowedDomains = emptyList(),
            browser = BrowserPolicy(blockedHosts = listOf("browser-only.example")),
        )
        assertTrue(filter.isHostBlocked("browser-only.example"))
    }

    @Test
    fun `extracts hosts from ordinary and awkward URLs`() {
        assertEquals("example.com", ContentFilter.hostOf("https://example.com/a/b"))
        assertEquals("example.com", ContentFilter.hostOf("https://user:pw@example.com:8443/a"))
        assertEquals("example.com", ContentFilter.hostOf("example.com/a"))
        assertEquals("example.com", ContentFilter.hostOf("HTTPS://EXAMPLE.COM"))
        assertNull(ContentFilter.hostOf("about:blank"))
    }

    @Test
    fun `the permissive filter blocks nothing`() {
        assertFalse(ContentFilter.PERMISSIVE.isHostBlocked("anything.example"))
        assertFalse(ContentFilter.PERMISSIVE.isUrlBlocked("https://anything.example/"))
    }

    private fun filterOf(
        blocked: List<String> = emptyList(),
        allowed: List<String> = emptyList(),
    ) = ContentFilter(DomainSet.of(blocked), DomainSet.of(allowed))
}
