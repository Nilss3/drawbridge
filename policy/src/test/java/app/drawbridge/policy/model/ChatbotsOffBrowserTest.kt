package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chatbots switch takes away from the browser while it is off, and gives
 * back when it is on: Google Search, Bing's Copilot, DuckDuckGo's AI answers and
 * every browser but herald.
 *
 * The shape is the same as for packages — a fragment blocks, the option spares —
 * so the switch keeps the rule that an option only ever releases.
 */
class ChatbotsOffBrowserTest {

    private val googleSearch = "^https?://(www\\.)?google\\.[a-z.]+/search"

    private val chatbots = PolicyOption(
        id = "chatbots",
        name = "Allow general-purpose chatbots",
        defaultEnabled = true,
        exemptUrlPatterns = listOf(googleSearch),
        exemptSearchEngines = listOf("Google"),
        exemptHostRewrites = listOf("duckduckgo.com"),
        exemptBrowserPackages = listOf("com.android.chrome"),
        exemptPackages = listOf("com.google.android.googlequicksearchbox"),
    )

    private val policy = Policy(
        version = 1,
        allowedBrowserPackages = listOf("app.drawbridge.herald", "com.android.chrome"),
        browser = BrowserPolicy(blockedUrlPatterns = listOf("existing")),
        conditional = listOf(
            ConditionalPolicy(
                minVersionCode = 70,
                options = listOf(chatbots),
                blockedUrlPatterns = listOf(googleSearch),
                hiddenSearchEngines = listOf("Google"),
                hostRewrites = mapOf("duckduckgo.com" to "noai.duckduckgo.com"),
                withheldBrowserPackages = listOf("com.android.chrome"),
                blockedPackages = listOf("com.google.android.googlequicksearchbox"),
            ),
        ),
    )

    @Test
    fun `off, the browser loses Google, Copilot's neighbours and the other browsers`() {
        val off = policy.effective(null, emptySet(), versionCode = 70)

        assertEquals(listOf("existing", googleSearch), off.browser.blockedUrlPatterns)
        assertEquals(listOf("DuckDuckGo", "Bing", "Kagi"), off.browser.visibleSearchEngines)
        assertEquals(mapOf("duckduckgo.com" to "noai.duckduckgo.com"), off.browser.hostRewrites)
        assertEquals(listOf("com.android.chrome"), off.withheldBrowserPackages)
        assertTrue("com.android.chrome" in off.browserPackages)
        assertTrue("com.google.android.googlequicksearchbox" in off.blockedPackages)
    }

    @Test
    fun `on, every one of them comes back`() {
        val on = policy.effective(null, setOf("chatbots"), versionCode = 70)

        assertEquals(listOf("existing"), on.browser.blockedUrlPatterns)
        assertEquals(listOf("DuckDuckGo", "Google", "Bing", "Kagi"), on.browser.visibleSearchEngines)
        assertEquals(emptyMap<String, String>(), on.browser.hostRewrites)
        assertEquals(emptyList<String>(), on.withheldBrowserPackages)
        assertTrue("com.google.android.googlequicksearchbox" in on.exemptPackages)
    }

    @Test
    fun `an older build is told none of it`() {
        val old = policy.effective(null, emptySet(), versionCode = 69)

        assertEquals(listOf("existing"), old.browser.blockedUrlPatterns)
        assertEquals(emptyList<String>(), old.browser.hiddenSearchEngines)
        assertEquals(emptyList<String>(), old.withheldBrowserPackages)
    }

    @Test
    fun `merging the fragments twice changes nothing`() {
        val once = policy.withConditionals(70)
        assertEquals(once, once.withConditionals(70))
    }
}
