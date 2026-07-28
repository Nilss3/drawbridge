package app.drawbridge.dpc

import android.app.Application
import android.content.Context
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.update.UpdateWorker
import app.drawbridge.policy.PolicyConfig
import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.work.PolicyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DrawbridgeApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch { policy(this@DrawbridgeApplication).ensureLoaded() }
        PolicyWorker.schedule(this)
        UpdateWorker.schedule(this)

        // Restrictions can be dropped by an OS upgrade, so re-assert them on every
        // process start rather than only at provisioning time.
        DeviceOwnerManager(this).applyManagedDevicePolicy()
    }

    companion object {
        /** drawbridge and herald read the same signed document from the same URL. */
        val policyConfig = PolicyConfig()

        fun policy(context: Context): PolicyManager =
            PolicyManager.getInstance(context, policyConfig)

        /** Called when the admin receiver is enabled or provisioning finishes. */
        fun onAdminEnabled(context: Context) {
            DeviceOwnerManager(context).applyManagedDevicePolicy()
            PolicyWorker.refreshNow(context)
            // Pulls herald down: by now every other browser is gone, so nothing
            // else on the device could fetch it.
            UpdateWorker.runNow(context)
        }
    }
}
