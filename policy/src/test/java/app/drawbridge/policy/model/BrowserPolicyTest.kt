package app.drawbridge.policy.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The block page's words come from the document, not from `values-xx`.
 *
 * That is deliberate — the wording can change without shipping a browser — but
 * it made the block page the one screen in herald that stayed English on a
 * Dutch phone while everything around it turned. These cover the fallback,
 * because a policy has to be able to add a language without every field being
 * translated in the same edit.
 */
class BrowserPolicyTest {

    private val browser = BrowserPolicy(
        blockedPageTitle = "drawbridge is up",
        blockedPageTitleByLanguage = mapOf("nl" to "drawbridge staat omhoog"),
        blockedPageMessage = "This website was denied access.",
        blockedPageMessageByLanguage = mapOf(
            "nl" to "Deze website kreeg geen toegang.",
            "fr" to "Ce site s'est vu refuser l'accès.",
        ),
    )

    @Test
    fun `uses the translation when the document carries one`() {
        assertEquals("drawbridge staat omhoog", browser.displayBlockedPageTitle("nl"))
        assertEquals("Ce site s'est vu refuser l'accès.", browser.displayBlockedPageMessage("fr"))
    }

    /** French has a message but no title here: the title must not blank out. */
    @Test
    fun `falls back per field, not per language`() {
        assertEquals("drawbridge is up", browser.displayBlockedPageTitle("fr"))
        assertEquals("Ce site s'est vu refuser l'accès.", browser.displayBlockedPageMessage("fr"))
    }

    @Test
    fun `falls back for a language the document has never heard of`() {
        assertEquals("drawbridge is up", browser.displayBlockedPageTitle("de"))
        assertEquals("This website was denied access.", browser.displayBlockedPageMessage("de"))
    }

    /**
     * An older install reading a newer document must not trip over the new
     * keys, and a document without them must still parse.
     */
    @Test
    fun `the i18n maps are optional in the document`() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<BrowserPolicy>(
            """{"blocked_page_title":"up"}""",
        )
        assertEquals("up", parsed.displayBlockedPageTitle("nl"))
        assertEquals(emptyMap<String, String>(), parsed.blockedPageTitleByLanguage)
    }

    /** A blank translation is not a translation; it falls back like a missing one. */
    @Test
    fun `a blank translation falls back`() {
        val blank = BrowserPolicy(
            blockedPageTitle = "drawbridge is up",
            blockedPageTitleByLanguage = mapOf("nl" to "   "),
        )
        assertEquals("drawbridge is up", blank.displayBlockedPageTitle("nl"))
    }
}
