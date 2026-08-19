package app.drawbridge.dpc.apps

import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a disallowed package may be removed, as a table.
 *
 * The rule has changed four times and every change was driven by a phone or by a
 * promise on the website rather than by reasoning, so it is worth having
 * somewhere that states it without needing a device:
 *
 *  - **before the first lock**, nothing is removed at all — that window is what
 *    lets a parent move their data across, and it is checked by the caller;
 *  - **what an option covers** waits for the lock, because the switch that
 *    decides its fate is one the parent may not have touched yet;
 *  - **a browser the policy sanctions but the browser choice has narrowed away**
 *    waits too, for exactly the same reason — it is a preference, and a
 *    reversible one;
 *  - **a browser the policy never sanctioned** goes whether the phone is locked
 *    or not, because it is a way around a DNS-only filter rather than one more
 *    blocked app;
 *  - **everything else the policy blocks** goes in either state as well.
 */
class AppBlockerRuleTest {

    // --- Now or at the lock --------------------------------------------------

    @Test
    fun `anything not deferred goes while the phone is unlocked`() {
        assertTrue(
            "an unlocked phone must not refill with the apps drawbridge was installed to remove",
            AppBlocker.actsNow(isDeferred = false, isLocked = false),
        )
    }

    @Test
    fun `a deferred package waits for the lock`() {
        assertFalse(
            "the parent may be halfway through changing its mind, and an uninstall is permanent",
            AppBlocker.actsNow(isDeferred = true, isLocked = false),
        )
    }

    @Test
    fun `a deferred package goes once locked`() {
        assertTrue(AppBlocker.actsNow(isDeferred = true, isLocked = true))
    }

    // --- What defers ---------------------------------------------------------

    private val policy = Policy(
        version = 1,
        allowedBrowserPackage = "app.drawbridge.herald",
        allowedBrowserPackages = listOf(BrowserSettings.MONO_PACKAGE, "com.android.chrome"),
        blockedPackages = listOf("com.instagram.android", "com.whatsapp", "com.opera.browser"),
        options = listOf(
            PolicyOption(
                id = "whatsapp",
                name = "Allow WhatsApp",
                exemptPackages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            ),
        ),
    )

    private fun allowed(choice: BrowserSettings.Choice) =
        BrowserSettings.allowedBrowsers(policy, choice)

    @Test
    fun `a package an option covers is deferred`() {
        assertTrue(
            AppBlocker.deferred("com.whatsapp", policy, allowed(BrowserSettings.Choice.ALL)),
        )
    }

    @Test
    fun `a package no option covers and no chooser touches is not deferred`() {
        assertFalse(
            "the policy's own blocklist has no second question attached to it",
            AppBlocker.deferred(
                "com.instagram.android",
                policy,
                allowed(BrowserSettings.Choice.ALL),
            ),
        )
    }

    /**
     * The case the browser chooser exists for, and the one that has to wait: the
     * website has promised since before this was built that the choice lands at
     * the lock, and a preinstalled Chrome that vanished the instant a parent
     * looked at the chooser would be the taunt the unlock window exists to
     * prevent.
     */
    @Test
    fun `a sanctioned browser the choice narrows away is deferred`() {
        assertTrue(
            "Chrome under 'only herald mono' is a preference, not a filter bypass",
            AppBlocker.deferred(
                "com.android.chrome",
                policy,
                allowed(BrowserSettings.Choice.MONO_ONLY),
            ),
        )
        assertTrue(
            "and under 'no browser' so is herald itself",
            AppBlocker.deferred(
                "app.drawbridge.herald",
                policy,
                allowed(BrowserSettings.Choice.NONE),
            ),
        )
    }

    /**
     * **The distinction the whole rule turns on.** Opera ships an in-browser
     * proxy over 443 that no DNS rule sees and `DISALLOW_CONFIG_VPN` does not
     * touch, so it is a hole in the filter rather than a preference — and it
     * closes whether the phone is locked or not. Nothing about the browser
     * chooser may soften that.
     */
    @Test
    fun `a browser the policy never sanctioned is never deferred, whatever the choice`() {
        BrowserSettings.Choice.entries.forEach { choice ->
            assertFalse(
                "under $choice, an unsanctioned browser is still a way around the filter",
                AppBlocker.deferred("com.opera.browser", policy, allowed(choice)),
            )
        }
    }

    @Test
    fun `an allowed browser is not deferred either, because it is not going anywhere`() {
        assertFalse(
            AppBlocker.deferred(
                "com.android.chrome",
                policy,
                allowed(BrowserSettings.Choice.ALL),
            ),
        )
    }

    // --- Which browsers a choice leaves ---------------------------------------

    @Test
    fun `every sanctioned browser survives the default choice`() {
        assertEquals(policy.browserPackages, allowed(BrowserSettings.Choice.ALL))
    }

    @Test
    fun `only herald mono survives the mono choice`() {
        assertEquals(setOf(BrowserSettings.MONO_PACKAGE), allowed(BrowserSettings.Choice.MONO_ONLY))
    }

    @Test
    fun `nothing survives the no-browser choice`() {
        assertTrue(allowed(BrowserSettings.Choice.NONE).isEmpty())
    }

    /**
     * Narrowing only, never widening. If a document ever stops sanctioning
     * herald mono, the mono choice has to stop allowing it too rather than
     * quietly out-ranking the document — the device-local setting is a filter on
     * the signed list, not a second source of truth beside it.
     */
    @Test
    fun `the mono choice cannot allow a browser the policy does not sanction`() {
        val withoutMono = policy.copy(allowedBrowserPackages = listOf("com.android.chrome"))

        assertTrue(
            BrowserSettings.allowedBrowsers(withoutMono, BrowserSettings.Choice.MONO_ONLY).isEmpty(),
        )
    }

    // --- Which packages an option governs -------------------------------------

    @Test
    fun `an option governs every package it exempts, on or off`() {
        val governed = AppBlocker.optionGoverned(policy.withOptions(emptySet()))

        assertTrue("com.whatsapp" in governed)
        assertTrue("com.whatsapp.w4b" in governed)
        assertFalse("com.instagram.android" in governed)
    }

    @Test
    fun `a policy with no options governs nothing`() {
        assertTrue(AppBlocker.optionGoverned(Policy(version = 1)).isEmpty())
    }

    // --- what the reason defers, not what the package defers ------------------

    /**
     * **The second Moto report of 2026-08-17, as a table.** With the install lock
     * on and drawbridge *unlocked*, TikTok, Firefox and Temu were installed and
     * all three stayed. They should have gone on sight: the blocklist, the
     * browser rule and the store rule all act continuously after the first lock.
     *
     * The cause was that deferral was asked about the *package* — and a newly
     * installed package is outside the install lock's set by definition, so every
     * arrival was deferred whatever else was wrong with it.
     *
     * Instagram is what exposed it, by working. It was on the phone at the
     * previous lock, so it was in the set, so nothing deferred it and the
     * blocklist removed it at once. These cases assert the property that was
     * missing: **a switch is what defers, and being new is not a switch.**
     */
    @Test
    fun `a blocklisted app is not deferred merely by being newly installed`() {
        assertFalse(
            "TikTok: on the blocked list, no switch governs it, so it goes on sight",
            AppBlocker.deferred("com.instagram.android", policy, allowed(BrowserSettings.Choice.ALL)),
        )
    }

    @Test
    fun `an unsanctioned browser is not deferred either`() {
        assertFalse(
            "Firefox: the policy never sanctioned it, so it is a filter bypass rather " +
                "than a preference, and it goes whether the phone is locked or not",
            AppBlocker.deferred("org.mozilla.firefox", policy, allowed(BrowserSettings.Choice.ALL)),
        )
    }

    /**
     * The case in that report that was **correct**, and the reason this rule is
     * not simply "act always". Crunchyroll is on the blocklist *and* covered by
     * the streaming option, so it waits for the lock like everything a switch can
     * still change — the parent may be about to turn streaming on.
     */
    @Test
    fun `an app an option covers still waits, even though it is also blocklisted`() {
        assertTrue(
            "WhatsApp stands in for Crunchyroll: blocked by name, exempted by a switch",
            AppBlocker.deferred("com.whatsapp", policy, allowed(BrowserSettings.Choice.ALL)),
        )
    }
}
