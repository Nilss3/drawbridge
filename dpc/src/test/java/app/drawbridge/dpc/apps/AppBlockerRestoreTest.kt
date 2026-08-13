package app.drawbridge.dpc.apps

import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a restore may bring back.
 *
 * This exists because of a bug the reference phone found on 2026-08-13 and no
 * test would have: switching *Allow YouTube* on left a hidden YouTube hidden
 * through a lock, an unlock and a reboot. The restore subtracted
 * `blocked_packages` from its candidates, and an option exempts a package
 * **without** removing it from that list — which is the shape of every option
 * this policy has. So the subtraction removed exactly what the option had just
 * allowed, and the restore only ever worked for a package that was allowed and
 * happened not to be blocked by name.
 */
class AppBlockerRestoreTest {

    private fun policyWithYouTubeOption(optionOn: Boolean): Policy {
        val base = Policy(
            version = 1,
            blockedPackages = listOf("com.google.android.youtube", "com.whatsapp"),
            options = listOf(
                PolicyOption(
                    id = "youtube",
                    name = "Allow YouTube",
                    exemptPackages = listOf("com.google.android.youtube"),
                ),
            ),
        )
        return base.withOptions(if (optionOn) setOf("youtube") else emptySet())
    }

    @Test
    fun `a package that is blocked by name and exempted by an option can be restored`() {
        val restorable = AppBlocker.restorable(policyWithYouTubeOption(optionOn = true))

        assertTrue(
            "an option exempts without unlisting, so subtracting the blocklist " +
                "removed exactly what the option allowed",
            "com.google.android.youtube" in restorable,
        )
    }

    @Test
    fun `an option that is off restores nothing of its own`() {
        val restorable = AppBlocker.restorable(policyWithYouTubeOption(optionOn = false))

        assertFalse("com.google.android.youtube" in restorable)
    }

    @Test
    fun `a package nobody allowed is never restorable`() {
        val restorable = AppBlocker.restorable(policyWithYouTubeOption(optionOn = true))

        assertFalse(
            "WhatsApp is blocked and its option is off, so nothing may bring it back",
            "com.whatsapp" in restorable,
        )
    }

    @Test
    fun `the allowed browsers are restorable, which is how a hidden Chrome comes back`() {
        val policy = Policy(
            version = 1,
            allowedBrowserPackage = "app.drawbridge.herald",
            allowedBrowserPackages = listOf("com.android.chrome"),
            blockedPackages = listOf("com.opera.browser"),
        )
        val restorable = AppBlocker.restorable(policy)

        assertTrue("com.android.chrome" in restorable)
        assertTrue("app.drawbridge.herald" in restorable)
        assertFalse("com.opera.browser" in restorable)
    }
}
