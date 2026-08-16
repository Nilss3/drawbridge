package app.drawbridge.dpc.admin

import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the one restriction whose membership is conditional, and one that is
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
 * [UserManager.DISALLOW_INSTALL_APPS] was briefly a second conditional entry and
 * lasted one build. It is retired now, and the second half of this file is about
 * making sure it stays that way on phones that already carry it.
 */
class DeviceOwnerRestrictionsTest {

    private fun restrictions(isLocked: Boolean, retainAdbAccess: Boolean = false) =
        DeviceOwnerManager.restrictionsFor(isLocked, retainAdbAccess)

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
     * The rule is meant to move exactly one entry. A change that quietly dropped
     * [UserManager.DISALLOW_SAFE_BOOT] or the multi-user restrictions on unlock
     * would leave an unlocked phone unfiltered rather than merely reachable, and
     * nothing else in the codebase would notice.
     */
    @Test
    fun `unlocking moves only the debugging restriction`() {
        val locked = restrictions(isLocked = true)
        val unlocked = restrictions(isLocked = false)

        assertEquals(
            setOf(UserManager.DISALLOW_DEBUGGING_FEATURES),
            locked.toSet() - unlocked.toSet(),
        )
        assertEquals(
            "unlocking must not add anything",
            emptySet<String>(),
            unlocked.toSet() - locked.toSet(),
        )
    }

    /**
     * [DeviceOwnerManager.applyUserRestrictions] clears whatever the current
     * state leaves out, and it computes that from [DeviceOwnerManager.MANAGED_RESTRICTIONS].
     * A conditional restriction that was not in that list would therefore be
     * applied and never cleared.
     */
    @Test
    fun `every conditional restriction is one applyUserRestrictions can clear`() {
        val everything = restrictions(isLocked = true)
        val nothing = restrictions(isLocked = false)

        assertTrue(
            "a restriction outside MANAGED_RESTRICTIONS can be set but never removed",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.containsAll(everything - nothing.toSet()),
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
        for (locked in listOf(true, false)) {
            for (adb in listOf(true, false)) {
                assertFalse(
                    "a locked phone must still be able to update what is on it",
                    restrictions(locked, adb).contains(UserManager.DISALLOW_INSTALL_APPS),
                )
            }
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
     * The whole conditional surface, stated once: exactly one entry moves, and
     * nothing else does in any combination. This is the assertion that catches a
     * future restriction being wired to the wrong condition.
     */
    @Test
    fun `only the debugging restriction ever moves`() {
        val all = listOf(true, false).flatMap { locked ->
            listOf(true, false).map { adb -> restrictions(locked, adb) }
        }
        val fixed = all.map { it.toSet() }.reduce { a, b -> a intersect b }

        assertEquals(
            setOf(UserManager.DISALLOW_DEBUGGING_FEATURES),
            DeviceOwnerManager.MANAGED_RESTRICTIONS.toSet() - fixed,
        )
    }
}
