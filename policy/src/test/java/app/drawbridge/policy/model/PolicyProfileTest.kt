package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyProfileTest {

    private val strict = Profile(
        id = "strict",
        name = "Strict",
        description = "Only what is on the list",
        dns = DnsPolicy(upstreams = listOf("9.9.9.9")),
        blockedPackages = listOf("com.example.game"),
        allowedPackages = listOf("com.example.school"),
    )

    private val relaxed = Profile(
        id = "relaxed",
        name = "Holiday",
        blockedPackages = emptyList(),
    )

    private val base = Policy(
        version = 1,
        dns = DnsPolicy(upstreams = listOf("1.1.1.1")),
        blockedPackages = listOf("com.example.social"),
        exemptPackages = listOf("com.example.school"),
        profiles = listOf(strict, relaxed),
        defaultProfile = "relaxed",
    )

    @Test
    fun `profile overrides only the fields it sets`() {
        val effective = base.withProfile("strict")

        assertEquals(listOf("9.9.9.9"), effective.dns.upstreams)
        assertEquals(listOf("com.example.game"), effective.blockedPackages)
        assertEquals(listOf("com.example.school"), effective.allowedPackages)
        // Untouched by the profile, so inherited from the base.
        assertEquals(listOf("com.example.school"), effective.exemptPackages)
        assertEquals(1, effective.version)
    }

    @Test
    fun `an empty list is an override, not an absence`() {
        // The difference that makes profiles usable: "block nothing" has to be
        // expressible, so emptyList() must win over the base's list.
        assertEquals(emptyList<String>(), base.withProfile("relaxed").blockedPackages)
    }

    @Test
    fun `no selection falls back to the default profile`() {
        assertEquals("relaxed", base.profileFor(null)?.id)
        assertEquals(emptyList<String>(), base.withProfile(null).blockedPackages)
    }

    @Test
    fun `an unknown selection degrades to the base rather than to nothing`() {
        // A device can hold a selection the policy has since dropped.
        val effective = base.copy(defaultProfile = null).withProfile("removed-profile")

        assertNull(effective.allowedPackages)
        assertEquals(listOf("com.example.social"), effective.blockedPackages)
    }

    @Test
    fun `a policy without profiles is returned unchanged`() {
        val plain = Policy(version = 2, blockedPackages = listOf("com.example.social"))
        assertTrue(plain.withProfile("anything") === plain)
    }

    @Test
    fun `allowlist mode is off unless a profile turns it on`() {
        assertNull(base.withProfile("relaxed").allowedPackages)
        assertEquals(listOf("com.example.school"), base.withProfile("strict").allowedPackages)
    }
}
