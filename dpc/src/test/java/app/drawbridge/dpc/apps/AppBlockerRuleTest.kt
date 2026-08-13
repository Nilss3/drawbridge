package app.drawbridge.dpc.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a disallowed package may be removed, as a table.
 *
 * The rule has changed twice in three days and both changes were driven by a
 * phone rather than by reasoning, so it is worth having somewhere that states it
 * without needing a device:
 *
 *  - **before the first lock**, nothing is removed at all — that window is what
 *    lets a parent move their data across, and it is checked by the caller;
 *  - **a browser** is removed whether the phone is locked or not, because the
 *    filter is DNS-only and a browser carrying its own proxy is a way around the
 *    filter rather than one more blocked app;
 *  - **everything else** waits for the lock, so an unlocked phone keeps what you
 *    install on it.
 */
class AppBlockerRuleTest {

    @Test
    fun `a browser is removed even while the phone is unlocked`() {
        assertTrue(
            "an unapproved browser on an unlocked phone is an unfiltered internet",
            AppBlocker.actsNow(isBrowser = true, isLocked = false),
        )
    }

    @Test
    fun `a browser is removed while locked, which never changed`() {
        assertTrue(AppBlocker.actsNow(isBrowser = true, isLocked = true))
    }

    @Test
    fun `anything else waits for the lock`() {
        assertFalse(
            "an unlock has to be a window, or data cannot be moved off the phone",
            AppBlocker.actsNow(isBrowser = false, isLocked = false),
        )
    }

    @Test
    fun `anything else is removed once locked`() {
        assertTrue(AppBlocker.actsNow(isBrowser = false, isLocked = true))
    }
}
