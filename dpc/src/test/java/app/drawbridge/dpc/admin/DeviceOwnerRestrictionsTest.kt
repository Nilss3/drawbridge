package app.drawbridge.dpc.admin

import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two restrictions whose membership is conditional, and one that is
 * retired.
 *
 * USB debugging is the project's only working delivery channel — Play Protect
 * refuses to install the DPC, so a cable is the way a fix reaches a phone — and
 * it is therefore keyed on the lock rather than on
 * [app.drawbridge.dpc.security.ParentKey.protectedSince]. Getting that backwards
 * in either direction is expensive and silent: applied while unlocked strands
 * every deployed handset, and left off while locked hands the removal route to
 * whoever is holding the phone.
 *
 * [UserManager.DISALLOW_FACTORY_RESET] is the second, and it is the more
 * expensive of the two to get wrong in one direction: it takes "Wipe
 * data/factory reset" out of the **hardware recovery menu** as well as out of
 * Settings, so a phone that carries it when it should not, and whose key is
 * gone, is reclaimable only by reflashing firmware from a PC. It is applied only
 * when the household has left trial mode *and* the phone is locked, and the
 * assertions below fix both halves of that.
 *
 * [UserManager.DISALLOW_INSTALL_APPS] was briefly a conditional entry too and
 * lasted one build. It is retired now, and the last part of this file is about
 * making sure it stays that way on phones that already carry it.
 */
class DeviceOwnerRestrictionsTest {

    private fun restrictions(
        isLocked: Boolean,
        retainAdbAccess: Boolean = false,
        isPermanent: Boolean = false,
    ) = DeviceOwnerManager.restrictionsFor(isLocked, retainAdbAccess, isPermanent)

    /** Every combination of the three inputs, for the assertions that sweep. */
    private fun everyState(): List<List<String>> =
        listOf(true, false).flatMap { locked ->
            listOf(true, false).flatMap { adb ->
                listOf(true, false).map { permanent -> restrictions(locked, adb, permanent) }
            }
        }

    @Test
    fun `locked release build takes usb debugging away`() {
        val restrictions = DeviceOwnerManager.restrictionsFor(
            isLocked = true,
            retainAdbAccess = false,
        )
        assertTrue(
            "a locked phone must not be reachable over adb",
            restrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES),
        )
    }

    @Test
    fun `unlocked release build leaves usb debugging available`() {
        val restrictions = DeviceOwnerManager.restrictionsFor(
            isLocked = false,
            retainAdbAccess = false,
        )
        assertFalse(
            "the parent holds the key, so the cable is theirs",
            restrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES),
        )
    }

    @Test
    fun `debug builds keep adb even when locked`() {
        val restrictions = DeviceOwnerManager.restrictionsFor(
            isLocked = true,
            retainAdbAccess = true,
        )
        assertFalse(
            "RETAIN_ADB_ACCESS is what makes a locked debug build testable",
            restrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES),
        )
    }

    /**
     * Online accounts are deliberately left alone, on the owner's decision of
     * 2026-08-10: people carry several legitimately, and the restriction blocks
     * removing them as well as adding them. A second *user* is the real hazard —
     * always-on VPN is per-user, so a guest profile would get unfiltered
     * network — and that is covered unconditionally.
     */
    @Test
    fun `accounts are never restricted, users always are`() {
        for (locked in listOf(true, false)) {
            val restrictions = DeviceOwnerManager.restrictionsFor(
                isLocked = locked,
                retainAdbAccess = false,
            )
            assertFalse(
                "adding an online account stays possible, locked or not",
                restrictions.contains(UserManager.DISALLOW_MODIFY_ACCOUNTS),
            )
            assertTrue(
                "a second user would get unfiltered network",
                restrictions.contains(UserManager.DISALLOW_ADD_USER),
            )
        }
    }

    /**
     * A debug build keeps adb so the device stays testable, and that flag must
     * not quietly take anything else with it — least of all the account rule,
     * which has nothing to do with debugging.
     */
    @Test
    fun `retaining adb withholds debugging and nothing else`() {
        val strict = restrictions(isLocked = true)
        val debug = restrictions(isLocked = true, retainAdbAccess = true)

        assertEquals(
            setOf(UserManager.DISALLOW_DEBUGGING_FEATURES),
            strict.toSet() - debug.toSet(),
        )
    }

    /**
     * Unlocking is meant to move a known set and nothing else. A change that
     * quietly dropped [UserManager.DISALLOW_SAFE_BOOT] or the multi-user
     * restrictions on unlock would leave an unlocked phone unfiltered rather
     * than merely reachable, and nothing else in the codebase would notice.
     *
     * What moves depends on the mode, which is the point: a trial phone gives
     * back only the cable, a permanent one gives back the cable and the wipe.
     */
    @Test
    fun `unlocking moves the debugging restriction, and in permanent mode the wipe too`() {
        for ((permanent, expected) in listOf(
            false to setOf(UserManager.DISALLOW_DEBUGGING_FEATURES),
            true to setOf(
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_FACTORY_RESET,
            ),
        )) {
            val locked = restrictions(isLocked = true, isPermanent = permanent)
            val unlocked = restrictions(isLocked = false, isPermanent = permanent)

            assertEquals(expected, locked.toSet() - unlocked.toSet())
            assertEquals(
                "unlocking must not add anything",
                emptySet<String>(),
                unlocked.toSet() - locked.toSet(),
            )
        }
    }

    /**
     * [DeviceOwnerManager.applyUserRestrictions] clears whatever the current
     * state leaves out, and it computes that from [DeviceOwnerManager.MANAGED_RESTRICTIONS].
     * A conditional restriction that was not in that list would therefore be
     * applied and never cleared.
     */
    @Test
    fun `every conditional restriction is one applyUserRestrictions can clear`() {
        val everything = everyState().flatten().toSet()

        assertTrue(
            "a restriction outside MANAGED_RESTRICTIONS can be set but never removed",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.containsAll(everything),
        )
    }

    // --- The factory reset, which permanent mode takes away ------------------

    /**
     * **The bottom-right cell of the matrix in
     * [app.drawbridge.dpc.security.Permanence], and the only one that costs
     * anything.** A permanent, locked phone cannot be wiped by whoever is
     * holding it — not from Settings and not from recovery — which is the whole
     * of what "permanent" buys and the whole of what it risks.
     */
    @Test
    fun `a permanent phone that is locked cannot be wiped`() {
        assertTrue(
            "permanent mode is this restriction; without it the mode is only a hidden menu item",
            restrictions(isLocked = true, isPermanent = true)
                .contains(UserManager.DISALLOW_FACTORY_RESET),
        )
    }

    /**
     * **The top-right cell, and the promise that keeps permanence from meaning
     * bricked.** Whoever holds the key can always unlock, and an unlocked phone
     * can always be wiped — so a permanent handset is reclaimable in two steps
     * by the parent, and after the lock screen's thirty-day timer by anybody
     * else. Applying this restriction on an unlocked phone would remove the last
     * way back from a device whose configuration screen is standing open.
     */
    @Test
    fun `a permanent phone that is unlocked can still be wiped`() {
        assertFalse(
            "unlocking is what gives the wipe back, and nothing else does",
            restrictions(isLocked = false, isPermanent = true)
                .contains(UserManager.DISALLOW_FACTORY_RESET),
        )
    }

    /**
     * Trial mode's entire promise, stated as an assertion: this phone can be
     * wiped, whatever else is true of it. It is the state every handset ships in
     * and the state the beta was handed out in.
     */
    @Test
    fun `trial mode never touches the factory reset`() {
        for (locked in listOf(true, false)) {
            for (adb in listOf(true, false)) {
                assertFalse(
                    "a phone nobody has made permanent must always be recoverable",
                    restrictions(locked, adb, isPermanent = false)
                        .contains(UserManager.DISALLOW_FACTORY_RESET),
                )
            }
        }
    }

    /**
     * **Deliberately not coupled to `RETAIN_ADB_ACCESS`**, unlike the debugging
     * rule directly above it. adb and the recovery menu are different doors, and
     * softening this one in debug builds would mean the restriction that matters
     * most is the one never exercised before it ships. Nothing is stranded by
     * it: `pm clear` on a debug handset drops the key, which unlocks the phone,
     * which clears the restriction on the next apply.
     */
    @Test
    fun `a debug build locks the factory reset just as a release build does`() {
        assertTrue(
            "a debug build must exercise the real restriction, not a softened one",
            restrictions(isLocked = true, retainAdbAccess = true, isPermanent = true)
                .contains(UserManager.DISALLOW_FACTORY_RESET),
        )
    }

    /**
     * **Retiring it and conditioning it have to be the same guarantee, or phones
     * in the field lose their wipe.** Builds up to 2026-08-07 applied this
     * restriction unconditionally, and until permanence existed it was cleared on
     * sight through [DeviceOwnerManager.RETIRED_RESTRICTIONS]. It is a
     * conditional member of [DeviceOwnerManager.MANAGED_RESTRICTIONS] now, which
     * keeps that guarantee only because [DeviceOwnerManager.applyUserRestrictions]
     * clears every managed restriction the current state leaves out — so a
     * trial-mode phone is still stripped of it on every apply.
     *
     * Being in both lists would be worse than being in neither: it would be set
     * and then cleared within the same apply, so permanent mode would silently
     * do nothing.
     */
    @Test
    fun `the factory reset restriction is managed rather than retired`() {
        assertTrue(
            "only membership of MANAGED_RESTRICTIONS gets it cleared from a trial phone",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.contains(UserManager.DISALLOW_FACTORY_RESET),
        )
        assertFalse(
            "in both lists it would be set and cleared in one apply, and permanence would be inert",
            DeviceOwnerManager.RETIRED_RESTRICTIONS.contains(UserManager.DISALLOW_FACTORY_RESET),
        )
    }

    // --- The install restriction, which is retired ---------------------------

    /**
     * **Measured on the owner's Moto G15 on 2026-08-16, one build after it was
     * added.** [UserManager.DISALLOW_INSTALL_APPS] was the install lock's
     * prevention layer, and the open question written down beside it was whether
     * the platform lets Play Store *updates* through. It does not — an attempt to
     * update Bitwarden was refused while the restriction was in force, because it
     * is checked in `PackageInstaller.createSession` and an update is an ordinary
     * session there.
     *
     * So no setting of this restriction can express *no new apps, updates fine*,
     * and blocking updates is worse than the feature is good: it freezes security
     * patches for every app on the phone. The install lock is carried entirely by
     * the closed set in `AppBlocker` now.
     */
    @Test
    fun `the install restriction is never applied, in any state`() {
        everyState().forEach { restrictions ->
            assertFalse(
                "a locked phone must still be able to update what is on it",
                restrictions.contains(UserManager.DISALLOW_INSTALL_APPS),
            )
        }
    }

    /**
     * **Retiring it is not the same as dropping it, and this is the assertion
     * that says so.** [DeviceOwnerManager.applyUserRestrictions] computes what to
     * clear from [DeviceOwnerManager.MANAGED_RESTRICTIONS], so a restriction
     * merely removed from that list stops being *set* on new devices and is never
     * taken off one that already carries it. Any phone that locked once under
     * build 29 would have been left unable to update anything, permanently, with
     * nothing on the device able to reach it.
     */
    @Test
    fun `the install restriction is cleared on sight from phones that carry it`() {
        assertTrue(
            "build 29 set it; every later build has to actively take it back off",
            DeviceOwnerManager.RETIRED_RESTRICTIONS.contains(UserManager.DISALLOW_INSTALL_APPS),
        )
        assertFalse(
            "and it must not be in both lists, or it would be set and cleared each apply",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.contains(UserManager.DISALLOW_INSTALL_APPS),
        )
    }

    /**
     * The whole conditional surface, stated once: exactly two entries move, and
     * nothing else does in any of the eight combinations. This is the assertion
     * that catches a future restriction being wired to the wrong condition — or
     * an existing one quietly becoming conditional, which is the direction that
     * would leave a locked phone unfiltered.
     */
    @Test
    fun `only the two conditional restrictions ever move`() {
        val fixed = everyState().map { it.toSet() }.reduce { a, b -> a intersect b }

        assertEquals(
            setOf(
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_FACTORY_RESET,
            ),
            DeviceOwnerManager.MANAGED_RESTRICTIONS.toSet() - fixed,
        )
    }
}
