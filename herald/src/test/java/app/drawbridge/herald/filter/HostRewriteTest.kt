package app.drawbridge.herald.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostRewriteTest {

    private val ddg = mapOf("duckduckgo.com" to "noai.duckduckgo.com")

    @Test
    fun `a DuckDuckGo search moves to the AI-free host with its query intact`() {
        assertEquals(
            "https://noai.duckduckgo.com/?q=cats&kp=1",
            HostRewrite.redirected("https://duckduckgo.com/?q=cats&kp=1", ddg),
        )
        assertEquals(
            "https://noai.duckduckgo.com/?q=cats",
            HostRewrite.redirected("https://www.duckduckgo.com/?q=cats", ddg),
        )
    }

    @Test
    fun `the rewritten URL is left alone, so nothing loops`() {
        assertNull(HostRewrite.redirected("https://noai.duckduckgo.com/?q=cats", ddg))
    }

    @Test
    fun `other hosts and lookalikes are untouched`() {
        assertNull(HostRewrite.redirected("https://example.com/?q=cats", ddg))
        assertNull(HostRewrite.redirected("https://duckduckgo.com.evil.example/?q=cats", ddg))
        assertNull(HostRewrite.redirected("https://html.duckduckgo.com/html?q=cats", ddg))
        assertNull(HostRewrite.redirected("https://duckduckgo.com/?q=cats", emptyMap()))
    }

    @Test
    fun `a bare host and a port survive`() {
        assertEquals("https://noai.duckduckgo.com", HostRewrite.redirected("https://duckduckgo.com", ddg))
        assertEquals(
            "http://noai.duckduckgo.com:8080/x",
            HostRewrite.redirected("http://duckduckgo.com:8080/x", ddg),
        )
    }
}
