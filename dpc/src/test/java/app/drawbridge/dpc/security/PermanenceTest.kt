package app.drawbridge.dpc.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trial and permanent mode, which is one boolean and three properties of it.
 *
 * The properties are worth asserting rather than reading off the field, because
 * each is load bearing somewhere else: the default is what every provisioned
 * phone runs and what the beta was handed out in, the one-way rule is what makes
 * "permanent" true rather than "one tap away from trial", and surviving a new
 * instance is what stops the restriction being re-derived as absent after a
 * process death.
 *
 * What this file deliberately does *not* test is the restriction. That lives in
 * [app.drawbridge.dpc.admin.DeviceOwnerRestrictionsTest], where the whole
 * conditional surface is checked at once — this is only the flag it reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PermanenceTest {

    private lateinit var permanence: Permanence

    @Before
    fun setUp() {
        permanence = Permanence(ApplicationProvider.getApplicationContext())
    }

    /**
     * **Trial mode is the default, and it has to be the one that is not
     * decided.** A phone arrives in the state that can still become the other
     * one; the parent presses a button to leave it. Defaulting the other way
     * would make every provisioned handset permanent before anybody was asked.
     */
    @Test
    fun `a phone starts in trial mode`() {
        assertFalse("nothing is permanent until somebody says so", permanence.isPermanent)
    }

    @Test
    fun `making it permanent is what the banner reads`() {
        permanence.makePermanent()
        assertTrue(permanence.isPermanent)
    }

    /**
     * **The one-way rule, asserted at the only level it can be: the API.** There
     * is no `makeTrial`, so this test is a compile-time fact dressed as a
     * runtime one — and that is the point of writing it down. A future
     * convenience setter would make permanent mode mean *trial mode plus one
     * tap*, and the top-right cell of the matrix would quietly become false: an
     * unlocked phone whose parent can return to trial mode can still be
     * deactivated from the menu.
     *
     * [Permanence.clear] is not that setter. It runs on the way out of
     * drawbridge entirely, after the restrictions are down, from a screen
     * permanence has already made unreachable.
     */
    @Test
    fun `permanence survives everything except leaving drawbridge`() {
        permanence.makePermanent()

        // A new instance, as after a process death: the flag is on disk, not in
        // the object, and the restriction is re-derived from it at every apply.
        val reread = Permanence(ApplicationProvider.getApplicationContext())
        assertTrue("a restart must not hand the phone back to trial mode", reread.isPermanent)

        reread.clear()
        assertFalse("removal takes it, and only removal", permanence.isPermanent)
    }
}
