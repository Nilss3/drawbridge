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

    /** Every case here is the default browser choice unless it says otherwise. */
    private fun restorableOf(policy: Policy, choice: BrowserSettings.Choice = BrowserSettings.Choice.ALL) =
        AppBlocker.restorable(policy, BrowserSettings.allowedBrowsers(policy, choice))

    @Test
    fun `a package that is blocked by name and exempted by an option can be restored`() {
        val restorable = restorableOf(policyWithYouTubeOption(optionOn = true))

        assertTrue(
            "an option exempts without unlisting, so subtracting the blocklist " +
                "removed exactly what the option allowed",
            "com.google.android.youtube" in restorable,
        )
    }

    @Test
    fun `an option that is off restores nothing of its own`() {
        val restorable = restorableOf(policyWithYouTubeOption(optionOn = false))

        assertFalse("com.google.android.youtube" in restorable)
    }

    @Test
    fun `a package nobody allowed is never restorable`() {
        val restorable = restorableOf(policyWithYouTubeOption(optionOn = true))

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
        val restorable = restorableOf(policy)

        assertTrue("com.android.chrome" in restorable)
        assertTrue("app.drawbridge.herald" in restorable)
        assertFalse("com.opera.browser" in restorable)
    }

    /**
     * **What makes "browsers come back after a relock with a different choice"
     * true**, which is the half of the browser chooser that can strand a phone.
     *
     * A preinstalled browser is hidden rather than uninstalled, and the only
     * thing that ever unhides one is `restoreNowAllowed` walking this list. So a
     * choice that narrows the list has to widen it again when it is undone —
     * and, crucially, must not restore while it is still in force, or the sweep
     * and the restore would fight over the same package every fifteen minutes.
     */
    @Test
    fun `a narrowed browser choice restores nothing, and undoing it restores everything`() {
        val policy = Policy(
            version = 1,
            allowedBrowserPackage = "app.drawbridge.herald",
            allowedBrowserPackages = listOf(BrowserSettings.MONO_PACKAGE, "com.android.chrome"),
        )

        val mono = restorableOf(policy, BrowserSettings.Choice.MONO_ONLY)
        assertTrue("herald mono is the one browser this choice keeps", BrowserSettings.MONO_PACKAGE in mono)
        assertFalse("so Chrome must stay hidden rather than be restored under it", "com.android.chrome" in mono)

        assertTrue(
            "and nothing at all comes back while no browser is allowed",
            restorableOf(policy, BrowserSettings.Choice.NONE).none { it in policy.browserPackages },
        )

        val all = restorableOf(policy, BrowserSettings.Choice.ALL)
        assertTrue("com.android.chrome" in all)
        assertTrue("app.drawbridge.herald" in all)
    }
}
