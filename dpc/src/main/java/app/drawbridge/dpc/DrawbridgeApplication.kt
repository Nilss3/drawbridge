package app.drawbridge.dpc

import android.app.Application
import android.content.Context
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
import app.drawbridge.dpc.security.ParentKey
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
        DeviceOwnerManager(this).reapplyIfProtected()
    }

    companion object {
        /** drawbridge and herald read the same signed document from the same URL. */
        val policyConfig = PolicyConfig()

        fun policy(context: Context): PolicyManager =
            PolicyManager.getInstance(context, policyConfig)

        /** Called when the admin receiver is enabled or provisioning finishes. */
        fun onAdminEnabled(context: Context) {
            DeviceOwnerManager(context).reapplyIfProtected()

            // Held back until the phone has actually been locked, for the same
            // reason the restrictions are. This runs during QR provisioning,
            // while the setup wizard is still on screen, and UpdateWorker.runNow
            // starts a ~470 MiB download of both browsers -- competing with the
            // wizard for the network at the exact moment it is trying to finish.
            // Nothing here is urgent: both workers are scheduled periodically,
            // and locking calls startEnforcing directly.
            if (ParentKey(context).protectedSince == 0L) {
                ProvisioningLog.record(context, "onAdminEnabled skipped (never locked)")
                return
            }
            startEnforcing(context)
        }

        /**
         * Fetches the current policy and installs whatever it requires.
         *
         * Called when the parent locks the phone, and from [onAdminEnabled] on a
         * device that is already protected. Not at provisioning time: on the QR
         * path that lands inside the setup wizard, and pulling both browsers down
         * underneath it is what left a Moto G15 unable to finish setup at all.
         */
        fun startEnforcing(context: Context) {
            ProvisioningLog.record(context, "startEnforcing: policy refresh + required apps")
            PolicyWorker.refreshNow(context)
            // Pulls herald down: by then every other browser is gone, so nothing
            // else on the device could fetch it.
            UpdateWorker.runNow(context)
        }
    }
}
