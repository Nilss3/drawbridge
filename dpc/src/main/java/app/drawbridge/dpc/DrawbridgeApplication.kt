package app.drawbridge.dpc

import android.app.Application
import android.content.Context
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
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

            // Held back for the same reason the restrictions are: this runs
            // during QR provisioning, while the setup wizard is still on screen,
            // and UpdateWorker.runNow starts a ~470 MiB download of both
            // browsers. Competing with the wizard for the network at the exact
            // moment it is finishing is not a fight worth picking. Both are
            // scheduled periodically anyway, and both run again on the next
            // process start, so waiting costs nothing but time.
            if (!ProvisioningLog.isSetupComplete(context)) {
                ProvisioningLog.record(context, "onAdminEnabled DEFERRED refresh+install")
                return
            }

            ProvisioningLog.record(context, "onAdminEnabled refresh+install")
            PolicyWorker.refreshNow(context)
            // Pulls herald down: by now every other browser is gone, so nothing
            // else on the device could fetch it.
            UpdateWorker.runNow(context)
        }
    }
}
