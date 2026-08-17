package app.drawbridge.dpc.apps

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 *  - a newcomer **waits for the lock**, because with this switched on unlocking
 *    is the only route a person has to add an app, and acting immediately would
 *    uninstall what the parent unlocked the phone to install;
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
    private lateinit var parentKey: ParentKey
    private lateinit var blocker: AppBlocker

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        settings = InstallLockSettings(context)
        settings.clear()
        parentKey = ParentKey(context)
        parentKey.clear()
        blocker = AppBlocker(context)
    }

    /** drawbridge's own browser: kept by `isProtected` under the default choice. */
    private val HERALD = "app.drawbridge.herald"

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
     * The case that would otherwise make the phone unusable: **once this is on**,
     * unlocking is the only route a person has to add an app, so one installed
     * during an unlock has to survive to the lock — where the snapshot is
     * re-taken with it in, and the question never arises again.
     */
    /**
     * The case that would otherwise make the phone unusable: **once this is on**,
     * unlocking is the only route a person has to add an app, so one installed
     * during an unlock has to survive to the lock — where the snapshot is
     * re-taken with it in, and the question never arises again.
     */
    @Test
    fun `being new waits for the lock`() {
        assertFalse(
            "removing it now would take away the app the parent just unlocked to install",
            AppBlocker.actsNow(isDeferred = true, isLocked = false),
        )
        assertTrue(
            "and it goes at the lock",
            AppBlocker.actsNow(isDeferred = true, isLocked = true),
        )
    }

    /**
     * **`deferred` asks about the package, not about the removal**, and
     * conflating the two cost build 32. It answers one question — is there a
     * *switch* still governing this app — and the install lock is not a switch,
     * so it must not appear here. What waits for the lock is decided per reason
     * inside `AppBlocker`, because an app can be new *and* disallowed and the
     * disallowed half is the one that decides.
     */
    @Test
    fun `the install lock is not a switch, so it does not belong in deferred`() {
        assertFalse(
            "nothing on the configuration screen governs an ordinary package",
            AppBlocker.deferred("com.example.newcomer", policy, allBrowsers),
        )
        assertTrue(
            "whereas an option's package is genuinely switch-governed",
            AppBlocker.deferred("com.whatsapp", policy, allBrowsers),
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

    // --- the install lock outranks every other rule ---------------------------

    /**
     * **The bug the Moto found on 2026-08-17, and the test that would have caught
     * it.** With *Only the apps already on this phone* on and the device locked,
     * Claude, DeepSeek and Session installed and stayed — all three are on the
     * policy whitelist — and so did Telegram, which an option allows.
     *
     * The cause was one line of precedence: `evaluate` asked `isProtected` first
     * and returned early, so every rule that answers *is this app acceptable*
     * short-circuited the one that answers *did this phone have it when it was
     * sealed*. Those are different questions, and the second cannot have
     * exceptions and still mean anything — "only the apps already on this phone"
     * is the entire promise.
     *
     * herald stands in for the whitelist here because it is reachable without
     * injecting a policy: `isProtected` keeps it via
     * `BuildConfig.ALLOWED_BROWSER_PACKAGE` under the default browser choice,
     * which is exactly the short-circuit that was wrong.
     */
    @Test
    fun `a protected package that arrived after the lock is still removed`() {
        parentKey.commit(ParentKey.generateKey())
        settings.isEnabled = true
        settings.take(listOf("com.example.wasalreadyhere"))

        val action = blocker.evaluate(HERALD)

        assertNotEquals(
            "an allowed browser is still an app this phone did not have when it was sealed",
            AppBlocker.Action.NONE,
            action,
        )
    }

    @Test
    fun `a protected package that was in the set is left alone`() {
        parentKey.commit(ParentKey.generateKey())
        settings.isEnabled = true
        settings.take(listOf(HERALD, "com.example.wasalreadyhere"))

        assertEquals(AppBlocker.Action.NONE, blocker.evaluate(HERALD))
    }

    /**
     * **Why removing the bypass costs herald nothing**, which is the half that
     * would strand a phone if it were wrong.
     *
     * herald never depended on `isProtected` to survive the install lock. It
     * survives because drawbridge puts what it installs *into the set* —
     * [InstallLockSettings.allow] before the session is committed, and
     * `closeTheInstalledSet` counting a still-downloading package as present. So
     * the reappearing-browser path is untouched by this fix, and it is untouched
     * for a reason that is visible here rather than asserted in a comment.
     */
    @Test
    fun `drawbridge's own install still survives, through the set rather than a bypass`() {
        parentKey.commit(ParentKey.generateKey())
        settings.isEnabled = true
        settings.take(listOf("com.example.wasalreadyhere"))

        // What AppInstaller.install does before committing the session.
        settings.allow(HERALD)

        assertEquals(
            "a browser drawbridge fetched itself is in the set by the time it lands",
            AppBlocker.Action.NONE,
            blocker.evaluate(HERALD),
        )
    }

    @Test
    fun `with the lock off, a protected package is protected as before`() {
        parentKey.commit(ParentKey.generateKey())
        settings.isEnabled = false
        settings.take(listOf("com.example.wasalreadyhere"))

        assertEquals(
            "the switch is off, so the closed set has no opinion about anything",
            AppBlocker.Action.NONE,
            blocker.evaluate(HERALD),
        )
    }

    /**
     * **The correction of 2026-08-17, as a test.** Several comments in this
     * codebase claimed the unlock window was *the only way to add an app*. It is
     * not, and stating it unqualified was wrong: this setting is off by default,
     * and a locked phone without it installs whatever the policy allows.
     *
     * What the switch changes is not *allowed versus not* — the blocklist, the
     * browser rule and the store rule are untouched by it — but *already here
     * versus not*.
     */
    @Test
    fun `with the lock off, a locked phone still accepts an ordinary new app`() {
        parentKey.commit(ParentKey.generateKey())
        settings.isEnabled = false
        settings.take(listOf("com.example.wasalreadyhere"))

        assertFalse(
            "the closed set has no opinion at all while the switch is off",
            InstallLockSettings.outsideTheSet(
                enabled = settings.isEnabled,
                snapshot = settings.snapshot,
                packageName = "com.example.arrivedjustnow",
            ),
        )
        assertEquals(
            "so an ordinary app the policy permits simply arrives, locked or not",
            AppBlocker.Action.NONE,
            blocker.evaluate("com.example.arrivedjustnow"),
        )
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
