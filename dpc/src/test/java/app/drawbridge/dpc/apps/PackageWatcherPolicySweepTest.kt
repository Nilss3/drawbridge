package app.drawbridge.dpc.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a policy that has just been installed earns a full sweep of every
 * package on the phone.
 *
 * **This is the gap the owner's Moto G15 found on 2026-08-26, reproduced
 * without the phone.** `com.dti.motorola` was named by policy 92 and 93 and was
 * not in the copy bundled in the APK, which is version 88. drawbridge fetched
 * the newer document, verified it, installed it, rebuilt the DNS filter from
 * it, and left the package sitting on the phone: nothing re-read the installed
 * set when the *document* changed. [PackageWatcher.sweepChanged] asks the
 * platform which packages changed, and a package does not change when the
 * policy does. The removal waited for the next service start, for the lock, or
 * for a sweep started from the settings screen — on the phone it looked like
 * the blocklist not working.
 *
 * The store rule could not cover for it, which is what made this reach a user.
 * A preinstalled system app with no launcher entry is outside
 * `withinStoreReach` entirely, and Play has no listing for an OEM preload
 * anyway, which is `UNVERIFIED`, which means keep. `blocked_packages` is the
 * only rail those packages have.
 *
 * Three cases, and two of them must not sweep — which is why the rule is a
 * named function rather than a comparison buried in a collector.
 */
class PackageWatcherPolicySweepTest {

    @Test
    fun `a newer policy sweeps`() {
        assertTrue(PackageWatcher.sweepsForPolicy(swept = 88, published = 93))
        assertTrue(PackageWatcher.sweepsForPolicy(swept = 92, published = 93))
    }

    /**
     * The steady state, and the common one: a `StateFlow` replays its current
     * value to every new collector, and the initial sweep in
     * [PackageWatcher.start] has already covered that version. Re-applying the
     * same document also re-emits at the same version whenever a profile is
     * chosen or an option is toggled, and the screen that makes those changes
     * sweeps for itself.
     */
    @Test
    fun `the same version does not sweep again`() {
        assertFalse(PackageWatcher.sweepsForPolicy(swept = 93, published = 93))
        assertFalse(PackageWatcher.sweepsForPolicy(swept = 0, published = 0))
    }

    /**
     * **The case that would do damage.** `PolicyManager.clear` publishes
     * `Policy(version = 0)` as part of the sanctioned removal flow. Sweeping
     * every package on the phone against an empty document is the one thing
     * this must never do, so the rule is strictly forward rather than "changed".
     */
    @Test
    fun `a policy going backwards never sweeps`() {
        assertFalse(PackageWatcher.sweepsForPolicy(swept = 93, published = 0))
        assertFalse(PackageWatcher.sweepsForPolicy(swept = 93, published = 92))
    }
}
