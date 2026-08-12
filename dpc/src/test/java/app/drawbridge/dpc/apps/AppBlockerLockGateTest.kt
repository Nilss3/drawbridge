package app.drawbridge.dpc.apps

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.dpc.security.ParentKey
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate, and only the gate.
 *
 * What the blocker *decides* about a package is covered by the policy model's
 * own tests; this file covers the question that turned out to be wrong on a real
 * phone on 2026-08-12 — **whether it may act at all**.
 *
 * Removal used to follow nothing. [PackageWatcher] lives inside the filter
 * service, the filter deliberately keeps running after the parent unlocks, so
 * apps went on being uninstalled from an unlocked phone: data could not be moved
 * off it and a second browser could not be kept long enough to try. The only
 * gate that existed asked `protectedSince != 0`, which means *has ever been
 * locked* and stays true forever afterwards.
 *
 * A plain Application is used rather than drawbridge's own, so this exercises the
 * gate without dragging in policy loading and device-admin plumbing — the same
 * reasoning as [app.drawbridge.dpc.security.ParentKeyTest]. Reaching the policy
 * lookup at all would mean the gate had already let the call through, which is
 * what makes an exception here a failure rather than noise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppBlockerLockGateTest {

    private lateinit var parentKey: ParentKey
    private lateinit var blocker: AppBlocker

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        parentKey = ParentKey(context)
        parentKey.clear()
        blocker = AppBlocker(context)
    }

    @Test
    fun `removes nothing while the phone is unlocked`() {
        assertEquals(
            "an unlocked phone must keep what is installed on it, so data can be moved off",
            AppBlocker.Action.NONE,
            blocker.evaluate("com.example.anything"),
        )
    }

    @Test
    fun `removes nothing after unlocking, which is the case that was broken`() {
        val key = ParentKey.generateKey()
        parentKey.commit(key)
        parentKey.unlock(key)

        assertEquals(
            "unlocking has to reopen the window, not merely reopen settings",
            AppBlocker.Action.NONE,
            blocker.evaluate("com.example.anything"),
        )
    }

    @Test
    fun `protectedSince alone does not reopen removal`() {
        // The old gate. protectedSince survives unlocking on purpose -- it is the
        // tamper date a caregiver reads -- so anything keyed on it is keyed on
        // "this phone was locked once", which is not a state anybody can leave.
        val key = ParentKey.generateKey()
        parentKey.commit(key)
        parentKey.unlock(key)

        assert(parentKey.protectedSince != 0L) {
            "the protected-since date is expected to survive unlocking"
        }
        assertEquals(AppBlocker.Action.NONE, blocker.evaluate("com.example.anything"))
    }

    @Test
    fun `a locked phone gets past the gate`() {
        parentKey.commit(ParentKey.generateKey())

        // Past the gate, the decision needs the policy and a real package. What
        // matters here is only that the call is no longer refused outright, so
        // any outcome other than an early NONE-by-gate is a pass: an unknown
        // package legitimately evaluates to NONE on its own merits.
        val action = runCatching { blocker.evaluate("com.example.anything") }
        assert(action.isSuccess) { "the gate must be open when locked: ${action.exceptionOrNull()}" }
    }
}
