package app.drawbridge.dpc.apps

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Apps the parent had switched off when they locked, and the rule that keeps
 * them off.
 *
 * The rule is four booleans across two functions and every one of them can be
 * got backwards, which is why they are pure and why this file is mostly about
 * them rather than about the storage. Getting `withholdNow` wrong in the
 * permissive direction leaves the hole this closes; getting `releaseNow` wrong
 * in the permissive direction hands back an app the *policy* still refuses,
 * which is worse than the hole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DisabledAppsTest {

    private lateinit var disabled: DisabledApps

    @Before
    fun setUp() {
        disabled = DisabledApps(ApplicationProvider.getApplicationContext())
    }

    // --- the set ------------------------------------------------------------

    /**
     * **Empty rather than null, and deliberately unlike
     * [InstallLockSettings.snapshot].** That one has to distinguish "never
     * taken" from "the phone carries nothing", because an empty *installed* set
     * is a rule that removes the whole device. This rule only ever withholds
     * packages it names, so both mean the same thing and there is no third state.
     */
    @Test
    fun `a phone that has never locked withholds nothing`() {
        assertEquals(emptySet<String>(), disabled.atLastLock)
    }

    @Test
    fun `the set survives a new instance, because the sweep re-reads it`() {
        disabled.take(listOf("com.example.clock", "com.example.games"))
        val reread = DisabledApps(ApplicationProvider.getApplicationContext())
        assertEquals(setOf("com.example.clock", "com.example.games"), reread.atLastLock)
    }

    /**
     * Every lock re-takes it, so unlocking, switching an app back on and locking
     * again is how a parent changes their mind. If [take] merged instead of
     * replacing, nothing could ever leave the set and an app the parent had
     * deliberately re-enabled would be withheld for the life of the phone.
     */
    @Test
    fun `taking it again replaces rather than merges`() {
        disabled.take(listOf("com.example.clock"))
        disabled.take(listOf("com.example.games"))
        assertEquals(setOf("com.example.games"), disabled.atLastLock)
    }

    @Test
    fun `removal clears it`() {
        disabled.take(listOf("com.example.clock"))
        disabled.clear()
        assertEquals(emptySet<String>(), disabled.atLastLock)
    }

    // --- withholding --------------------------------------------------------

    /** The case the whole feature exists for: switched off, then switched on. */
    @Test
    fun `an app switched back on is withheld`() {
        assertTrue(
            DisabledApps.withholdNow(
                inSet = true,
                stillDisabled = false,
                alreadyWithheld = false,
            ),
        )
    }

    @Test
    fun `an app still switched off is left alone`() {
        assertFalse(
            "it is already off; hiding it as well would be drawbridge acting for no reason",
            DisabledApps.withholdNow(
                inSet = true,
                stillDisabled = true,
                alreadyWithheld = false,
            ),
        )
    }

    @Test
    fun `an app already hidden is not hidden twice`() {
        assertFalse(
            DisabledApps.withholdNow(
                inSet = true,
                stillDisabled = false,
                alreadyWithheld = true,
            ),
        )
    }

    /**
     * The set is the whole authority. An app the parent never switched off is
     * not drawbridge's business, whatever else is true of it, and this is the
     * assertion that stops the rule growing into one that withholds by some
     * other criterion.
     */
    @Test
    fun `an app the parent never switched off is never withheld`() {
        for (stillDisabled in listOf(true, false)) {
            for (withheld in listOf(true, false)) {
                assertFalse(
                    DisabledApps.withholdNow(
                        inSet = false,
                        stillDisabled = stillDisabled,
                        alreadyWithheld = withheld,
                    ),
                )
            }
        }
    }

    // --- releasing ----------------------------------------------------------

    @Test
    fun `unlocking gives back what was held for the lock`() {
        assertTrue(
            DisabledApps.releaseNow(inSet = true, withheld = true, policyDisallows = false),
        )
    }

    /**
     * **The assertion that matters most in this file.** A package can be hidden
     * for two reasons at once: the parent switched it off, and the signed policy
     * refuses it. WhatsApp with its option off is exactly that. Releasing it
     * because the lock ended would hand the phone an app the document still
     * disallows, and drawbridge would be undoing its own policy on the parent's
     * behalf.
     */
    @Test
    fun `an app the policy also refuses is not released`() {
        assertFalse(
            DisabledApps.releaseNow(inSet = true, withheld = true, policyDisallows = true),
        )
    }

    @Test
    fun `nothing is released that was not withheld`() {
        assertFalse(
            DisabledApps.releaseNow(inSet = true, withheld = false, policyDisallows = false),
        )
        assertFalse(
            DisabledApps.releaseNow(inSet = false, withheld = true, policyDisallows = false),
        )
    }

    /**
     * The two rules must never both fire for one package in one state, or a
     * sweep would hide it and hand it straight back. They are kept apart by the
     * lock rather than by their inputs — `holdDisabled` returns early when
     * unlocked and `releaseDisabledHolds` when locked — and this checks the
     * inputs cannot do it either: withholding needs it *not* withheld, releasing
     * needs it withheld.
     */
    @Test
    fun `withholding and releasing never both apply`() {
        for (stillDisabled in listOf(true, false)) {
            for (withheld in listOf(true, false)) {
                for (disallows in listOf(true, false)) {
                    val hold = DisabledApps.withholdNow(true, stillDisabled, withheld)
                    val release = DisabledApps.releaseNow(true, withheld, disallows)
                    assertFalse(
                        "hold=$hold release=$release for " +
                            "stillDisabled=$stillDisabled withheld=$withheld disallows=$disallows",
                        hold && release,
                    )
                }
            }
        }
    }
}
