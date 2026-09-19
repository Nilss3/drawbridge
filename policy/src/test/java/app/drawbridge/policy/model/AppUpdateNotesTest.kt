package app.drawbridge.policy.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What an update says about itself.
 *
 * The update screen is the only place a parent is asked to take an action
 * drawbridge cannot take for itself, and until these two fields existed the only
 * thing it could tell them was that a larger integer existed somewhere. What is
 * covered here is that a document which says nothing still produces a sentence,
 * because every policy written before this did.
 */
class AppUpdateNotesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val update = AppUpdate(
        packageName = "app.drawbridge.dpc",
        versionCode = 51,
        url = "https://example.invalid/dpc.apk",
        sha256 = "00",
        versionName = "0.2.26",
        notes = "WhatsApp calls work again.",
        notesByLanguage = mapOf("nl" to "WhatsApp-oproepen werken weer."),
    )

    @Test
    fun `the dotted version is preferred when the document names one`() {
        assertEquals("0.2.26", update.displayVersion())
    }

    @Test
    fun `a document that names no version falls back to the build number`() {
        assertEquals("build 51", update.copy(versionName = null).displayVersion())
    }

    @Test
    fun `notes are translated, and fall back rather than blanking`() {
        assertEquals("WhatsApp-oproepen werken weer.", update.displayNotes("nl"))
        assertEquals("WhatsApp calls work again.", update.displayNotes("fr"))
    }

    /**
     * Null and blank have to collapse to the same answer, because the screen
     * shows a heading and a body together — a blank body under a *What changed*
     * heading is worse than neither.
     */
    @Test
    fun `an update with no notes says nothing rather than saying nothing loudly`() {
        assertNull(update.copy(notes = null).displayNotes("en"))
        assertNull(update.copy(notes = "   ").displayNotes("en"))
    }

    @Test
    fun `a document written before these fields still parses`() {
        val policy = json.decodeFromString<Policy>(
            """
            {
              "version": 1,
              "app_update": {
                "package_name": "app.drawbridge.dpc",
                "version_code": 50,
                "url": "https://example.invalid/dpc.apk",
                "sha256": "00"
              }
            }
            """.trimIndent(),
        )

        val parsed = requireNotNull(policy.appUpdate)
        assertNull(parsed.versionName)
        assertNull(parsed.displayNotes("en"))
        assertEquals("build 50", parsed.displayVersion())
    }

    @Test
    fun `version_name and notes_i18n parse under their document names`() {
        val policy = json.decodeFromString<Policy>(
            """
            {
              "version": 1,
              "app_update": {
                "package_name": "app.drawbridge.dpc",
                "version_code": 51,
                "url": "https://example.invalid/dpc.apk",
                "sha256": "00",
                "version_name": "0.2.26",
                "notes": "WhatsApp calls work again.",
                "notes_i18n": { "fr": "Les appels WhatsApp fonctionnent à nouveau." }
              }
            }
            """.trimIndent(),
        )

        val parsed = requireNotNull(policy.appUpdate)
        assertEquals("0.2.26", parsed.displayVersion())
        assertEquals("Les appels WhatsApp fonctionnent à nouveau.", parsed.displayNotes("fr"))
    }
}
