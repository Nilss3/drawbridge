package app.drawbridge.dpc.admin

import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two restrictions whose membership is conditional.
 *
 * USB debugging is the project's only working delivery channel — Play Protect
 * refuses to install the DPC, so a cable is the way a fix reaches a phone — and
 * it is therefore keyed on the lock rather than on
 * [app.drawbridge.dpc.security.ParentKey.protectedSince]. Getting that backwards
 * in either direction is expensive and silent: applied while unlocked strands
 * every deployed handset, and left off while locked hands the removal route to
 * whoever is holding the phone.
 *
 * [UserManager.DISALLOW_INSTALL_APPS] joined it on 2026-08-16 and takes a third
 * condition, the household's own install-lock switch, because it is the only
 * restriction here that is off by default. Its failure modes are the same shape:
 * applied to a phone whose parent never asked for it, a school's app cannot be
 * installed without the key; withheld from one that did ask, the Play Store is
 * wide open on a locked phone and the closed set is left carrying the promise
 * alone.
 */
class DeviceOwnerRestrictionsTest {

    /** Both defaults are the ordinary release-build, lock-only-what-was-asked-for case. */
    private fun restrictions(
        isLocked: Boolean,
        retainAdbAccess: Boolean = false,
        installLock: Boolean = false,
    ) = DeviceOwnerManager.restrictionsFor(isLocked, retainAdbAccess, installLock)

    @Test
    fun `locked release build takes usb debugging away`() {
        val restrictions = DeviceOwnerManager.restrictionsFor(
            isLocked = true,
            retainAdbAccess = false,
            installLock = false,
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
            installLock = false,
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
            installLock = false,
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
                installLock = false,
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
        val everything = restrictions(isLocked = true, installLock = true)
        val nothing = restrictions(isLocked = false)

        assertTrue(
            "a restriction outside MANAGED_RESTRICTIONS can be set but never removed",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.containsAll(everything - nothing.toSet()),
        )
    }

    // --- The install lock ----------------------------------------------------

    /**
     * The default, and the state every phone before 2026-08-16 was in. A parent
     * who has not asked for this must be able to install a bank app on a locked
     * phone exactly as before.
     */
    @Test
    fun `a locked phone whose parent did not ask for it can still install apps`() {
        assertFalse(
            "off by default: it changes what the phone is, not what it filters",
            restrictions(isLocked = true, installLock = false)
                .contains(UserManager.DISALLOW_INSTALL_APPS),
        )
    }

    @Test
    fun `a locked phone whose parent asked for it cannot`() {
        assertTrue(
            restrictions(isLocked = true, installLock = true)
                .contains(UserManager.DISALLOW_INSTALL_APPS),
        )
    }

    /**
     * **Keyed on the lock, not on protection**, exactly as USB debugging is, and
     * for a plainer reason: installing something is the main thing a parent
     * unlocks the phone *to do*. A restriction that survived unlocking would
     * make the one documented way to add an app — unlock, install, lock again —
     * impossible, which would leave the key opening a phone that still refuses.
     */
    @Test
    fun `unlocking lets the parent install, which is what they unlocked for`() {
        assertFalse(
            restrictions(isLocked = false, installLock = true)
                .contains(UserManager.DISALLOW_INSTALL_APPS),
        )
    }

    /**
     * The two conditional entries are independent. Switching the install lock on
     * must not quietly hand back the cable, and a debug build keeping adb must
     * not quietly open installs.
     */
    @Test
    fun `the install lock and the debugging rule do not move each other`() {
        val withLock = restrictions(isLocked = true, installLock = true)
        assertTrue(
            "the install lock says nothing about adb",
            withLock.contains(UserManager.DISALLOW_DEBUGGING_FEATURES),
        )

        val debugBuild = restrictions(isLocked = true, retainAdbAccess = true, installLock = true)
        assertTrue(
            "and retaining adb says nothing about installs",
            debugBuild.contains(UserManager.DISALLOW_INSTALL_APPS),
        )
        assertFalse(debugBuild.contains(UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    /**
     * The whole conditional surface, stated once: exactly two entries move, and
     * nothing else does in any combination. This is the assertion that catches a
     * future third restriction being wired to the wrong condition.
     */
    @Test
    fun `only the two conditional restrictions ever move`() {
        val all = listOf(true, false).flatMap { locked ->
            listOf(true, false).flatMap { adb ->
                listOf(true, false).map { lock -> restrictions(locked, adb, lock) }
            }
        }
        val fixed = all.map { it.toSet() }.reduce { a, b -> a intersect b }

        assertEquals(
            setOf(
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_INSTALL_APPS,
            ),
            DeviceOwnerManager.MANAGED_RESTRICTIONS.toSet() - fixed,
        )
    }
}
