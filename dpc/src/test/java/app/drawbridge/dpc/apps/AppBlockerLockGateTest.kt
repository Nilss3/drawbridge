package app.drawbridge.dpc.apps

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.security.ParentKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the blocker may act, which is the question this file has always covered
 * and the answer to which changed twice.
 *
 * **2026-08-12**: removal followed nothing at all. [PackageWatcher] lives inside
 * the filter service, the filter deliberately keeps running after the parent
 * unlocks, so apps went on being uninstalled from an unlocked phone — data could
 * not be moved off it and a second browser could not be kept long enough to try.
 *
 * **2026-08-17**: the fix for that had gone one step too far in the other
 * direction. A phone that had never been locked removed *nothing*, so a
 * drawbridge somebody installed and never locked was a filter with no app rules
 * at all — and not everybody is going to lock. That gate is gone. What decides
 * now is [AppBlocker.actsNow]: an app no switch can bring back goes as soon as
 * drawbridge has read a policy, and one a switch still governs waits for the
 * lock, because the parent has not answered that question yet.
 *
 * The bundled default policy is loaded here rather than mocked, because the two
 * cases below are exactly "on the policy's list" and "on the policy's list but
 * governed by an option", and a hand-made document would be free to disagree with
 * the one the app ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppBlockerLockGateTest {

    private lateinit var parentKey: ParentKey
    private lateinit var blocker: AppBlocker

    /** On the bundled policy's blocklist, and governed by no option. */
    private val blocked = "com.instagram.android"

    /** On the same list, and rescued by the *Allow WhatsApp* switch. */
    private val optionGoverned = "com.whatsapp"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        parentKey = ParentKey(context)
        parentKey.clear()
        blocker = AppBlocker(context)
        runBlocking { DrawbridgeApplication.policy(context).ensureLoaded() }
    }

    @Test
    fun `a blocked app goes before the phone has ever been locked`() {
        // The change of 2026-08-17, in one assertion. protectedSince is zero
        // here: nothing has been locked, and the app goes anyway, because no
        // control on the configuration screen was ever going to keep it.
        assertNotEquals(
            "a drawbridge that is installed but never locked still has to remove this",
            AppBlocker.Action.NONE,
            blocker.evaluate(blocked),
        )
    }

    @Test
    fun `an app a switch still governs waits for the lock`() {
        // The other half, and the reason the gate is per-reason rather than
        // global: WhatsApp is on the same list, and *Allow WhatsApp* is a
        // question the parent has not been asked yet on a phone this new.
        assertEquals(
            "removing it now would answer a question that belongs to the parent",
            AppBlocker.Action.NONE,
            blocker.evaluate(optionGoverned),
        )
    }

    /**
     * **The whole switch table, against the document that actually ships**, and
     * the check the owner asked for before build 36 went out: a drawbridge that
     * has just been installed must not take away the four things a parent is
     * about to be asked about.
     *
     * Every one of these is on `blocked_packages` — that is what makes the case
     * worth pinning. The blocklist is the branch that acts from installation now,
     * and the *only* thing standing between it and WhatsApp on a phone nobody has
     * configured yet is `deferred` saying an option governs this package. One
     * wrong answer here is a parent who never got the choice.
     */
    @Test
    fun `none of the four toggles is removed on a phone that has never been locked`() {
        val governed = mapOf(
            "com.whatsapp" to "Allow WhatsApp",
            "com.google.android.youtube" to "Allow YouTube",
            "org.telegram.messenger" to "Allow Telegram",
            "com.netflix.mediaclient" to "Allow video streaming",
        )

        for ((packageName, switch) in governed) {
            assertEquals(
                "$packageName is blocklisted but $switch still governs it, " +
                    "so a fresh install must leave it alone",
                AppBlocker.Action.NONE,
                blocker.evaluate(packageName),
            )
        }
    }

    /**
     * The same four, still safe once the phone *is* locked but the parent has
     * left the switches off — because at that point the removal is the answer
     * they gave, and it is `sweepOnLock` that carries it out rather than a
     * background sweep on an unconfigured phone.
     */
    @Test
    fun `the toggles are what decides them, not the calendar`() {
        val allowedBrowsers = BrowserSettings.allowedBrowsers(
            DrawbridgeApplication.policy(
                ApplicationProvider.getApplicationContext<android.app.Application>(),
            ).policy.value,
            BrowserSettings.Choice.ALL,
        )
        val policy = DrawbridgeApplication.policy(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).policy.value

        for (packageName in listOf(
            "com.whatsapp",
            "com.google.android.youtube",
            "org.telegram.messenger",
            "com.netflix.mediaclient",
        )) {
            assertTrue(
                "$packageName must be governed by a switch, or nothing defers it",
                AppBlocker.deferred(packageName, policy, allowedBrowsers),
            )
        }
        assertFalse(
            "and Instagram must not be, or the blocklist would never act",
            AppBlocker.deferred("com.instagram.android", policy, allowedBrowsers),
        )
    }

    @Test
    fun `an app no rule names is left alone in every state`() {
        assertEquals(AppBlocker.Action.NONE, blocker.evaluate("com.example.anything"))

        val key = ParentKey.generateKey()
        parentKey.commit(key)
        assertEquals(AppBlocker.Action.NONE, blocker.evaluate("com.example.anything"))

        parentKey.unlock(key)
        assertEquals(AppBlocker.Action.NONE, blocker.evaluate("com.example.anything"))
    }

    @Test
    fun `unlocking does not reopen what an option governs`() {
        // The 2026-08-12 case, still true and still worth pinning: a parent
        // unlocks to move data across, and what a switch governs must survive
        // that window rather than being swept fifteen minutes later.
        val key = ParentKey.generateKey()
        parentKey.commit(key)
        parentKey.unlock(key)

        assertEquals(AppBlocker.Action.NONE, blocker.evaluate(optionGoverned))
    }

    /**
     * The browser rule is the one branch that must not act on an unread
     * document, because it removes what is *not* named and an empty [Policy]
     * names herald alone. See [AppBlocker.browserRuleApplies]; the version here
     * is the sentinel `PolicyManager` starts with.
     */
    @Test
    fun `the browser rule waits for a document to have been read`() {
        assertEquals(false, AppBlocker.browserRuleApplies(isBrowser = true, policyVersion = 0))
        assertEquals(true, AppBlocker.browserRuleApplies(isBrowser = true, policyVersion = 1))
        assertEquals(false, AppBlocker.browserRuleApplies(isBrowser = false, policyVersion = 37))
    }
}
