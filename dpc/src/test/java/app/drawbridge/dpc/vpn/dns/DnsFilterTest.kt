package app.drawbridge.dpc.vpn.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeSearchTargetTest {

    @Test
    fun `redirects google search domains regardless of country`() {
        listOf("google.com", "www.google.com", "google.be", "google.co.uk", "www.google.de")
            .forEach { domain ->
                assertEquals(
                    "expected $domain to be redirected",
                    "forcesafesearch.google.com",
                    DnsFilter.safeSearchTargetFor(domain),
                )
            }
    }

    @Test
    fun `leaves google infrastructure domains alone`() {
        // Redirecting these would break unrelated apps: they are not search
        // front ends and do not honour the safe-search hostname.
        listOf(
            "googleapis.com",
            "www.googleapis.com",
            "googleusercontent.com",
            "google-analytics.com",
            "clients4.google.com",
        ).forEach { domain ->
            assertNull("expected $domain to be left alone", DnsFilter.safeSearchTargetFor(domain))
        }
    }

    @Test
    fun `redirects youtube to restricted mode`() {
        listOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com")
            .forEach { domain ->
                assertEquals(
                    "restrictmoderate.youtube.com",
                    DnsFilter.safeSearchTargetFor(domain),
                )
            }
    }

    @Test
    fun `redirects bing and duckduckgo`() {
        assertEquals("strict.bing.com", DnsFilter.safeSearchTargetFor("www.bing.com"))
        assertEquals("safe.duckduckgo.com", DnsFilter.safeSearchTargetFor("duckduckgo.com"))
    }

    @Test
    fun `leaves unrelated domains alone`() {
        assertNull(DnsFilter.safeSearchTargetFor("example.com"))
        assertNull(DnsFilter.safeSearchTargetFor("notgoogle.com"))
    }
}
