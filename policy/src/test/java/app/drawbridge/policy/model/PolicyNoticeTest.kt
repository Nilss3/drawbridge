package app.drawbridge.policy.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one channel this project has for saying something to the people running
 * the phones.
 *
 * What is worth testing here is small and all of it is the *absence* cases:
 * a document that carries no notice, one that carries a half-written one, and
 * one written before the field existed. Those are the three that reach a phone
 * by accident, and the one that must never draw an empty card is the middle one.
 */
class PolicyNoticeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val notice = PolicyNotice(
        id = "2026-09-calls",
        title = "WhatsApp calls are fixed",
        body = "Update drawbridge to get the fix.",
        titleByLanguage = mapOf("nl" to "WhatsApp-oproepen werken weer"),
        bodyByLanguage = mapOf("nl" to "Werk drawbridge bij voor de oplossing."),
    )

    @Test
    fun `a translated notice uses the language asked for`() {
        assertEquals("WhatsApp-oproepen werken weer", notice.displayTitle("nl"))
        assertEquals("Werk drawbridge bij voor de oplossing.", notice.displayBody("nl"))
    }

    @Test
    fun `a language the notice was not translated into falls back rather than blanking`() {
        assertEquals("WhatsApp calls are fixed", notice.displayTitle("fr"))
        assertEquals("Update drawbridge to get the fix.", notice.displayBody("fr"))
    }

    @Test
    fun `a complete notice is drawable`() {
        assertTrue(notice.isDrawable())
    }

    /**
     * The case that would put an empty card on a parent's screen. A notice is
     * the one field in this document whose failure mode is *visible furniture
     * around nothing*, so the guard is here rather than in the view.
     */
    @Test
    fun `a notice missing any of its three required parts is not drawable`() {
        assertFalse(notice.copy(id = "").isDrawable())
        assertFalse(notice.copy(title = "").isDrawable())
        assertFalse(notice.copy(body = "").isDrawable())
        assertFalse(notice.copy(title = "   ").isDrawable())
    }

    @Test
    fun `a document with no notice is the resting state`() {
        val policy = json.decodeFromString<Policy>("""{"version": 1}""")
        assertNull(policy.notice)
    }

    /**
     * A document written before this field existed must stay valid, which is the
     * same promise every other added field has made — see `excluded_packages`.
     * The reverse also has to hold, and does: both parsers set
     * `ignoreUnknownKeys`, so a build too old to know about `notice` ignores it
     * rather than rejecting the whole document.
     */
    @Test
    fun `a notice parses from the document and keeps its optional url`() {
        val policy = json.decodeFromString<Policy>(
            """
            {
              "version": 1,
              "notice": {
                "id": "2026-09-calls",
                "title": "WhatsApp calls are fixed",
                "body": "Update drawbridge to get the fix.",
                "url": "https://drawbridge-project.pages.dev/",
                "title_i18n": { "nl": "WhatsApp-oproepen werken weer" }
              }
            }
            """.trimIndent(),
        )

        val parsed = requireNotNull(policy.notice)
        assertEquals("2026-09-calls", parsed.id)
        assertEquals("https://drawbridge-project.pages.dev/", parsed.url)
        assertEquals("WhatsApp-oproepen werken weer", parsed.displayTitle("nl"))
        assertTrue(parsed.isDrawable())
    }

    @Test
    fun `a notice without a url is the ordinary case`() {
        assertNull(notice.url)
    }
}
