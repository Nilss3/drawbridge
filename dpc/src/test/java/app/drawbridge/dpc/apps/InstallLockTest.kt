package app.drawbridge.dpc.apps

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The install lock: the closed set, and when it is allowed to act.
 *
 * The promise is one sentence — *no new app installs after locking, and updates
 * of the apps already there still come through* — and it is expressed as a set
 * rather than as a date or as `EXTRA_REPLACING`. Everything worth checking
 * follows from that choice, so this file checks the choice rather than the
 * plumbing:
 *
 *  - an **update** is in the set by construction, because an update never adds a
 *    package name that was not already there. There is no test for "updates are
 *    allowed" that is not this one;
 *  - a snapshot that has **never been taken** is not an empty one. An empty set
 *    means *this phone carries nothing*, which would make the rule remove the
 *    entire device;
 *  - a newcomer **waits for the lock**, because the unlock window is the only way
 *    to add an app and acting immediately would uninstall what the parent
 *    unlocked the phone to install;
 *  - **herald survives**, twice over, and that is the half that can strand a
 *    phone — see the last two tests.
 *
 * A plain Application is used for the Robolectric half, the same reasoning as
 * [AppBlockerLockGateTest]: this exercises the settings and the rule without
 * dragging in policy loading and device-admin plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class InstallLockTest {

    private lateinit var settings: InstallLockSettings

    @Before
    fun setUp() {
        settings = InstallLockSettings(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )
        settings.clear()
    }

    // --- The rule, without a device -----------------------------------------

    @Test
    fun `the lock switched off says nothing about anything`() {
        assertFalse(
            "off is off: a phone without the install lock is the phone every other build was",
            InstallLockSettings.outsideTheSet(
                enabled = false,
                snapshot = setOf("com.example.one"),
                packageName = "com.example.newcomer",
            ),
        )
    }

    @Test
    fun `a snapshot that was never taken is not an empty one`() {
        // The whole reason `snapshot` is nullable. An empty set reads as "this
        // phone carries nothing", so every package on it would be a newcomer and
        // the rule would take the device apart — the same shape of mistake as
        // keying enforcement on protectedSince, which reads as "has ever been
        // locked" and means something else.
        assertFalse(
            "a phone that has never been locked has no set to be outside of",
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = null,
                packageName = "com.example.anything",
            ),
        )
    }

    @Test
    fun `a package that was on the phone at the lock stays`() {
        assertFalse(
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = setOf("com.example.one", "com.example.two"),
                packageName = "com.example.two",
            ),
        )
    }

    @Test
    fun `a package that was not is a newcomer`() {
        assertTrue(
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = setOf("com.example.one"),
                packageName = "com.example.newcomer",
            ),
        )
    }

    /**
     * **This is the "updates still come through" test**, and it looks like
     * nothing because that is the point of using a set.
     *
     * An update arrives as `ACTION_PACKAGE_ADDED` with `EXTRA_REPLACING` true and
     * the *same package name* as the copy it replaces — so it is in the snapshot
     * by construction, whatever its version, and the rule cannot fire on it.
     * Nothing anywhere in the app has to read that flag or compare versions.
     *
     * The alternative designs both fail here: a rule keyed on install *time*
     * would see a fresh `lastUpdateTime` and remove the app, and a rule keyed on
     * `EXTRA_REPLACING` would have no answer at all for the fifteen-minute sweep,
     * which has no broadcast to read the flag from.
     */
    @Test
    fun `an update of an installed app is never a newcomer`() {
        val snapshot = setOf("com.example.messenger")
        assertFalse(
            "an update never adds a package name that was not already there",
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = snapshot,
                packageName = "com.example.messenger",
            ),
        )
    }

    // --- When it is allowed to act ------------------------------------------

    private val policy = Policy(
        version = 1,
        allowedBrowserPackage = "app.drawbridge.herald",
        allowedBrowserPackages = listOf(BrowserSettings.MONO_PACKAGE, "com.android.chrome"),
        blockedPackages = listOf("com.instagram.android"),
        options = listOf(
            PolicyOption(
                id = "whatsapp",
                name = "Allow WhatsApp",
                exemptPackages = listOf("com.whatsapp"),
            ),
        ),
    )

    private val allBrowsers =
        BrowserSettings.allowedBrowsers(policy, BrowserSettings.Choice.ALL)

    /**
     * The case that would otherwise make the phone unusable: unlocking is the
     * *only* way to add an app once this is on, so an app installed during an
     * unlock has to survive to the lock — where the snapshot is re-taken with it
     * in, and the question never arises again.
     */
    @Test
    fun `a newcomer waits for the lock`() {
        assertTrue(
            AppBlocker.deferred(
                "com.example.newcomer",
                policy,
                allBrowsers,
                outsideInstalledSet = true,
            ),
        )
        assertFalse(
            "removing it now would take away the app the parent just unlocked to install",
            AppBlocker.actsNow(isDeferred = true, isLocked = false),
        )
        assertTrue(
            "and it goes at the lock, like everything else a switch governs",
            AppBlocker.actsNow(isDeferred = true, isLocked = true),
        )
    }

    @Test
    fun `the install lock does not defer anything on its own`() {
        assertFalse(
            "a package inside the set is not this rule's business at all",
            AppBlocker.deferred(
                "com.example.ordinary",
                policy,
                allBrowsers,
                outsideInstalledSet = false,
            ),
        )
    }

    // --- The stored set ------------------------------------------------------

    @Test
    fun `the lock is off until somebody switches it on`() {
        assertFalse(
            "it changes what the phone is, so nobody gets it by leaving a button unpressed",
            settings.isEnabled,
        )
        assertNull("and there is no set before the first lock", settings.snapshot)
        assertEquals(0L, settings.snapshotTakenAt)
    }

    @Test
    fun `taking the set records what was on the phone, and when`() {
        settings.take(listOf("com.example.one", "com.example.two"))

        assertEquals(setOf("com.example.one", "com.example.two"), settings.snapshot)
        assertTrue("the timestamp is what Diagnostics reads", settings.snapshotTakenAt > 0)
    }

    @Test
    fun `re-taking it at the next lock is how an app gets added`() {
        settings.take(listOf("com.example.one"))
        // The parent unlocks, installs something, and locks again.
        settings.take(listOf("com.example.one", "com.example.bank"))

        assertFalse(
            "unlock, install, lock again — that is the whole way in",
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = settings.snapshot,
                packageName = "com.example.bank",
            ),
        )
    }

    /**
     * The loop this exists to prevent, and it is the same loop the browser choice
     * already had to be guarded against.
     *
     * `required_apps` names herald. herald is user-installed, so a browser-policy
     * change *uninstalls* it rather than hiding it, and the next poll fetches
     * 230 MB to put it back — on a locked phone, where the install lock would
     * then remove it as a package outside the set. drawbridge would spend the
     * rest of the phone's life downloading and deleting its own browser.
     */
    @Test
    fun `an app drawbridge installs itself joins the set`() {
        settings.take(listOf("com.example.one"))
        settings.allow("app.drawbridge.herald")

        assertFalse(
            "drawbridge fetching herald is the parent's decision arriving by proxy",
            InstallLockSettings.outsideTheSet(
                enabled = true,
                snapshot = settings.snapshot,
                packageName = "app.drawbridge.herald",
            ),
        )
        assertEquals(
            "and it adds rather than replaces",
            setOf("com.example.one", "app.drawbridge.herald"),
            settings.snapshot,
        )
    }

    /**
     * Belt and braces, and the braces are the part that matters: herald is an
     * *allowed browser*, so `AppBlocker.isProtected` declines it before the
     * install-lock branch is ever reached. [InstallLockSettings.allow] is the
     * general answer — it covers any required app, and any future one — but the
     * browser this build ships would survive without it.
     */
    @Test
    fun `herald is an allowed browser, which the install lock never overrides`() {
        assertTrue(
            "the policy sanctions it, and isProtected is consulted before every rule",
            "app.drawbridge.herald" in allBrowsers,
        )
        assertFalse(
            "so the closed set is never even asked about it under 'the allowed browsers'",
            AppBlocker.deferred("app.drawbridge.herald", policy, allBrowsers),
        )
    }

    /**
     * The half [InstallLockSettings.allow] cannot cover on its own, and the one
     * that had to be found by re-reading rather than by running anything.
     *
     * herald is over 200 MB. *Choose the allowed browsers, then lock* commits the
     * install and then spends minutes downloading — and the lock lands in the
     * middle of it, re-taking the snapshot from the packages actually on the
     * phone. herald is not one of them yet, so the name `allow` had carefully
     * put in the set is written straight back out, and drawbridge removes the
     * browser it is still fetching.
     *
     * Counting an in-flight install as present is what closes it. This is the
     * unit-level statement of the rule; the caller that unions the two is
     * [AppBlocker.closeTheInstalledSet].
     */
    @Test
    fun `a package still downloading counts as being on the phone`() {
        InstallLockSettings.beginOwnInstall("app.drawbridge.herald")
        try {
            assertTrue(
                "the two ends of an install are minutes apart, and a lock fits between them",
                "app.drawbridge.herald" in InstallLockSettings.ownInstallsInFlight,
            )

            // What closeTheInstalledSet writes: the phone, plus what is on its way.
            val onThePhone = listOf("com.example.one")
            settings.take(onThePhone + InstallLockSettings.ownInstallsInFlight)

            assertFalse(
                "a lock during the download must not evict the app it is downloading",
                InstallLockSettings.outsideTheSet(
                    enabled = true,
                    snapshot = settings.snapshot,
                    packageName = "app.drawbridge.herald",
                ),
            )
        } finally {
            InstallLockSettings.endOwnInstall("app.drawbridge.herald")
        }
        assertTrue(
            "and the window closes when the platform reports back",
            InstallLockSettings.ownInstallsInFlight.isEmpty(),
        )
    }

    @Test
    fun `allow does nothing before the first lock`() {
        // There is no set to add to, and nothing is being removed in that window
        // either. Writing one here would invent a snapshot out of a single
        // package name and make every other app on the phone a newcomer.
        settings.allow("app.drawbridge.herald")
        assertNull(settings.snapshot)
    }

    @Test
    fun `removal takes the set with it`() {
        settings.isEnabled = true
        settings.take(listOf("com.example.one"))

        settings.clear()

        assertFalse(settings.isEnabled)
        assertNull(settings.snapshot)
    }
}
