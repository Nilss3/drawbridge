package app.drawbridge.dpc.admin

import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the one restriction whose membership is conditional.
 *
 * USB debugging is the project's only working delivery channel — Play Protect
 * refuses to install the DPC, so a cable is the way a fix reaches a phone — and
 * it is therefore the one restriction keyed on the lock rather than on
 * [app.drawbridge.dpc.security.ParentKey.protectedSince]. Getting that backwards
 * in either direction is expensive and silent: applied while unlocked strands
 * every deployed handset, and left off while locked hands the removal route to
 * whoever is holding the phone.
 */
class DeviceOwnerRestrictionsTest {

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
        val strict = DeviceOwnerManager.restrictionsFor(isLocked = true, retainAdbAccess = false)
        val debug = DeviceOwnerManager.restrictionsFor(isLocked = true, retainAdbAccess = true)

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
        val locked = DeviceOwnerManager.restrictionsFor(isLocked = true, retainAdbAccess = false)
        val unlocked = DeviceOwnerManager.restrictionsFor(isLocked = false, retainAdbAccess = false)

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
        val locked = DeviceOwnerManager.restrictionsFor(isLocked = true, retainAdbAccess = false)
        val unlocked = DeviceOwnerManager.restrictionsFor(isLocked = false, retainAdbAccess = false)

        assertTrue(
            "a restriction outside MANAGED_RESTRICTIONS can be set but never removed",
            DeviceOwnerManager.MANAGED_RESTRICTIONS.containsAll(locked - unlocked.toSet()),
        )
    }
}
