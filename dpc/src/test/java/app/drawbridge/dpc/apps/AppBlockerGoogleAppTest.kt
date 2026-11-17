package app.drawbridge.dpc.apps

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
import app.drawbridge.dpc.security.ParentKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Google app with chatbots switched off and the phone locked, against the
 * document the build ships. **Found on the owner's Moto G15 on 2026-11-17**:
 * build 71's lock sweep left it untouched and never even logged it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppBlockerGoogleAppTest {

    private lateinit var context: android.app.Application
    private lateinit var blocker: AppBlocker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setDeviceOwner(DrawbridgeDeviceAdminReceiver.componentName(context))
        ParentKey(context).clear()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = GOOGLE
                applicationInfo = ApplicationInfo().apply {
                    packageName = GOOGLE
                    flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                }
            },
        )
        val manager = DrawbridgeApplication.policy(context)
        runBlocking {
            manager.ensureLoaded()
            manager.setOptionEnabled(CHATBOTS, false)
        }
        blocker = AppBlocker(context)
    }

    @Test
    fun `chatbots off puts the Google app on the blocked list`() {
        val policy = DrawbridgeApplication.policy(context).policy.value
        assertTrue("chatbots must be off", CHATBOTS !in DrawbridgeApplication.policy(context).enabledOptionIds)
        assertTrue("blocked: ${policy.blockedPackages.filter { "google" in it }}", GOOGLE in policy.blockedPackages)
        assertTrue("must not be exempt", GOOGLE !in policy.exemptPackages)
    }

    @Test
    fun `a locked phone suspends the Google app when chatbots are off`() {
        ParentKey(context).commit(ParentKey.generateKey())
        assertEquals(AppBlocker.Action.SUSPENDED, blocker.evaluate(GOOGLE))
    }

    @Test
    fun `the Moto's history - chatbots on, then off, the other four on, then the lock`() {
        val manager = DrawbridgeApplication.policy(context)
        runBlocking {
            for (id in listOf("whatsapp", "youtube", "telegram", "streaming", CHATBOTS)) {
                manager.setOptionEnabled(id, true)
            }
            manager.setOptionEnabled(CHATBOTS, false)
        }
        val policy = manager.policy.value
        println("enabled=${manager.enabledOptionIds} exempt has G=${GOOGLE in policy.exemptPackages} blocked has G=${GOOGLE in policy.blockedPackages}")
        println("standings G=${blocker.standings()[GOOGLE]}")
        ParentKey(context).commit(ParentKey.generateKey())
        val swept = blocker.sweep()
        println("sweep=$swept")
        assertEquals(AppBlocker.Action.SUSPENDED, swept[GOOGLE])
    }

    private companion object {
        const val GOOGLE = "com.google.android.googlequicksearchbox"
        const val CHATBOTS = "general-purpose-chatbots"
    }
}
