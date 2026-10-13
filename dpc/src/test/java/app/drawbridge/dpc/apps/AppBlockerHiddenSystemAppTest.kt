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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A system app drawbridge has already hidden must never be sent down the
 * uninstall path.
 *
 * **Found on the owner's Moto G15 on 2026-10-13.** A hidden package is invisible
 * to an ordinary `getApplicationInfo`, so the system-app check read Motorola's
 * app-pusher, `com.dti.motorola`, as an ordinary app that was not there, and
 * `remove` uninstalled it. Android cannot remove a system app, so the uninstall
 * stripped its update and wiped its data instead — and the pusher woke up as if
 * on a new phone, updated itself, and installed a batch of games, which
 * drawbridge then removed one by one. It happened fifteen minutes after every
 * restart of drawbridge, on the first periodic sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppBlockerHiddenSystemAppTest {

    private lateinit var context: Application
    private lateinit var blocker: AppBlocker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setDeviceOwner(DrawbridgeDeviceAdminReceiver.componentName(context))

        val info = PackageInfo().apply {
            packageName = PUSHER
            applicationInfo = ApplicationInfo().apply {
                packageName = PUSHER
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            }
        }
        shadowOf(context.packageManager).installPackage(info)
        blocker = AppBlocker(context)
    }

    @Test
    fun `a hidden system app is hidden again rather than uninstalled`() {
        hide(PUSHER)

        val action = blocker.remove(PUSHER)

        assertNotEquals(
            "uninstalling a hidden system app strips its update and wipes its data",
            AppBlocker.Action.UNINSTALLED,
            action,
        )
        assertEquals(AppBlocker.Action.HIDDEN, action)
    }

    /** What a hidden package looks like to the rest of the phone: not there. */
    private fun hide(packageName: String) {
        blocker.hideOrSuspend(packageName)
        // A hidden platform API, so reflection; Robolectric's package manager
        // implements it and then answers lookups the way a phone does.
        context.packageManager.javaClass
            .getMethod(
                "setApplicationHiddenSettingAsUser",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                android.os.UserHandle::class.java,
            )
            .invoke(context.packageManager, packageName, true, android.os.Process.myUserHandle())
        check(
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isFailure,
        ) { "the shadow has to hide the package from an ordinary lookup for this test to mean anything" }
        check(
            runCatching {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES,
                )
            }.isSuccess,
        )
    }

    private companion object {
        const val PUSHER = "com.dti.motorola"
    }
}
