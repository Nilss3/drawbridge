package app.drawbridge.dpc.apps

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDevicePolicyManager

/**
 * The ladder that makes a blocked app unopenable: hide, and failing that
 * suspend.
 *
 * **This is the bug the owner's Moto G15 found on 2026-08-14, reproduced without
 * the phone.** Switching *Allow YouTube* off and locking left
 * `com.google.android.youtube` sitting there usable while
 * `com.google.android.apps.youtube.music` went, and the cause was not a rule
 * this app got wrong: `setApplicationHidden` simply **returns false** for the
 * first package on that handset and true for the second. Both are preloaded
 * system apps, both are named the same way by the policy, and both take an
 * identical path through [AppBlocker]. Nothing in the 522 tests that existed
 * could fail, because nothing exercised what happens when the platform says no.
 *
 * Robolectric's `failSetApplicationHiddenFor` is exactly that handset's
 * behaviour, so the two packages are tested as a pair rather than singly — this
 * file's own project has now been bitten four times in one build by a rule
 * checked against the one example that happened to work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppBlockerLadderTest {

    private lateinit var context: Application
    private lateinit var dpm: DevicePolicyManager
    private lateinit var shadowDpm: ShadowDevicePolicyManager
    private lateinit var blocker: AppBlocker

    private val admin get() = DrawbridgeDeviceAdminReceiver.componentName(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowDpm = shadowOf(dpm)
        shadowDpm.setDeviceOwner(admin)

        install(YOUTUBE)
        install(MUSIC)

        blocker = AppBlocker(context)
    }

    @Test
    fun `a package the platform refuses to hide is suspended instead`() {
        shadowDpm.failSetApplicationHiddenFor(listOf(YOUTUBE))

        assertEquals(
            "a refused hide used to return FAILED and leave the app usable on a locked phone",
            AppBlocker.Action.SUSPENDED,
            blocker.hideOrSuspend(YOUTUBE),
        )
        assertTrue("the app has to end up unopenable one way or the other", isSuspended(YOUTUBE))
    }

    @Test
    fun `the package beside it still hides, which is how the bug presented`() {
        shadowDpm.failSetApplicationHiddenFor(listOf(YOUTUBE))

        assertEquals(
            "hiding is the first rung and suspension only the fallback",
            AppBlocker.Action.HIDDEN,
            blocker.hideOrSuspend(MUSIC),
        )
        assertTrue(dpm.isApplicationHidden(admin, MUSIC))

        // Whether it is *also* suspended cannot be asserted, and finding that out
        // is worth the line: `isPackageSuspended` throws NameNotFoundException
        // for a hidden package, because a hidden package answers no queries at
        // all. That is the same property that stops `restorable` being
        // generalised, and the reason AppBlocker reads both states through
        // runCatching rather than trusting either to answer.
        assertThrows(PackageManager.NameNotFoundException::class.java) { isSuspended(MUSIC) }
    }

    @Test
    fun `hiding and suspending are told apart, because the log has to say which`() {
        shadowDpm.failSetApplicationHiddenFor(listOf(YOUTUBE))

        // These were one enum value named SUSPENDED and meaning *hidden* until
        // 2026-08-15, so a phone doing the fallback and a phone doing the normal
        // thing wrote the same line. Build 27 added that logging precisely to
        // tell branches apart; one value for two mechanisms undoes it.
        assertEquals(AppBlocker.Action.SUSPENDED, blocker.hideOrSuspend(YOUTUBE))
        assertEquals(AppBlocker.Action.HIDDEN, blocker.hideOrSuspend(MUSIC))
    }

    @Test
    fun `a package that can be neither hidden nor suspended is reported as failed`() {
        // The end of the ladder. It has to be distinguishable from success,
        // because this is the one outcome a parent needs to be able to see:
        // policy disallows the app and the phone will still open it.
        assertEquals(AppBlocker.Action.FAILED, blocker.hideOrSuspend("com.example.never.installed"))
    }

    @Test
    fun `removal gives back what was suspended as well as what was hidden`() {
        shadowDpm.failSetApplicationHiddenFor(listOf(YOUTUBE))
        blocker.hideOrSuspend(YOUTUBE)
        blocker.hideOrSuspend(MUSIC)
        assertTrue(isSuspended(YOUTUBE))
        assertTrue(dpm.isApplicationHidden(admin, MUSIC))

        blocker.unhideAll()

        // This is the half that strands a phone: drawbridge is gone, so nothing
        // is left that could un-suspend anything it left behind.
        assertFalse("a suspended app on an unmanaged phone can never be freed", isSuspended(YOUTUBE))
        assertFalse(dpm.isApplicationHidden(admin, MUSIC))
    }

    private fun isSuspended(packageName: String): Boolean =
        dpm.isPackageSuspended(admin, packageName)

    /** A preloaded system app, which is what both packages in this file are. */
    private fun install(packageName: String) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val MUSIC = "com.google.android.apps.youtube.music"
    }
}
