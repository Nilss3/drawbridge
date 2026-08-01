package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyOptionTest {

    private val whatsapp = PolicyOption(
        id = "whatsapp",
        name = "Allow WhatsApp",
        recommendedAge = 14,
        exemptPackages = listOf("com.whatsapp"),
        allowedDomains = listOf("whatsapp.net"),
        allowedPackages = listOf("com.whatsapp"),
    )

    private val music = PolicyOption(
        id = "music",
        name = "Allow music streaming",
        defaultEnabled = true,
        exemptPackages = listOf("com.spotify.music"),
    )

    private val base = Policy(
        version = 1,
        blockedPackages = listOf("com.whatsapp", "com.spotify.music"),
        exemptPackages = listOf("com.example.school"),
        allowedDomains = listOf("school.example"),
        options = listOf(whatsapp, music),
    )

    @Test
    fun `an option exempts its packages and allows its domains`() {
        val effective = base.withOptions(setOf("whatsapp"))

        assertEquals(listOf("com.example.school", "com.whatsapp"), effective.exemptPackages)
        assertEquals(listOf("school.example", "whatsapp.net"), effective.allowedDomains)
        // The base list is untouched: an option adds an exemption rather than
        // rewriting what is blocked, so turning it off restores the base exactly.
        assertEquals(listOf("com.whatsapp", "com.spotify.music"), effective.blockedPackages)
    }

    @Test
    fun `options that are off change nothing`() {
        assertTrue(base.withOptions(emptySet()) === base)
        assertTrue(base.withOptions(setOf("not-an-option")) === base)
    }

    @Test
    fun `an option cannot switch allowlist mode on`() {
        // allowedPackages stays null, because a policy that suddenly started
        // uninstalling every unlisted app would be the opposite of a relaxation.
        assertNull(base.withOptions(setOf("whatsapp")).allowedPackages)
    }

    @Test
    fun `an option extends an allowlist a profile has already turned on`() {
        val allowlisted = base.copy(allowedPackages = listOf("com.example.school"))

        assertEquals(
            listOf("com.example.school", "com.whatsapp"),
            allowlisted.withOptions(setOf("whatsapp")).allowedPackages,
        )
    }

    @Test
    fun `nothing stored means the policy's own defaults`() {
        assertEquals(setOf("music"), base.enabledOptionIds(null))
    }

    @Test
    fun `an empty stored list is a choice, not an absence`() {
        // Otherwise clearing the last option would silently switch the
        // default-on ones back on at the next policy load.
        assertEquals(emptySet<String>(), base.enabledOptionIds(emptyList()))
    }

    @Test
    fun `a stored option the policy has dropped is forgotten`() {
        assertEquals(setOf("whatsapp"), base.enabledOptionIds(listOf("whatsapp", "gone")))
    }

    @Test
    fun `profile then options`() {
        val holiday = Profile(id = "holiday", name = "Holiday", exemptPackages = emptyList())
        val policy = base.copy(profiles = listOf(holiday))

        val effective = policy.effective("holiday", setOf("whatsapp"))

        // The profile cleared the base's exemptions; the option's survive it,
        // because options are applied after.
        assertEquals(listOf("com.whatsapp"), effective.exemptPackages)
    }

    @Test
    fun `the picker's words follow the app's language, and fall back rather than blank`() {
        val translated = whatsapp.copy(
            nameByLanguage = mapOf("nl" to "WhatsApp toestaan"),
            descriptionByLanguage = mapOf("nl" to "", "fr" to "Autorise WhatsApp."),
        )

        assertEquals("WhatsApp toestaan", translated.displayName("nl"))
        assertEquals("Allow WhatsApp", translated.displayName("fr"))
        assertEquals("Allow WhatsApp", translated.displayName("en"))
        // An empty translation is a gap, not a decision to show nothing.
        assertEquals("", translated.displayDescription("nl"))
        assertEquals("Autorise WhatsApp.", translated.displayDescription("fr"))
    }

    @Test
    fun `duplicate exemptions are not repeated`() {
        val policy = base.copy(exemptPackages = listOf("com.whatsapp"))

        assertEquals(listOf("com.whatsapp"), policy.withOptions(setOf("whatsapp")).exemptPackages)
    }
}
