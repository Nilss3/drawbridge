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

            // Herald comes down now, before the phone is locked, and that is a
            // deliberate reversal.
            //
            // It used to wait for the lock, because this method ran *inside* the
            // QR setup wizard and starting a ~470 MiB download underneath the
            // wizard is what left a Moto G15 unable to finish setup at all. With
            // the QR path retired there is no such moment left: provisioning
            // happens over a cable, on a phone that has already finished setup.
            //
            // And there is a reason to want it early. The window before the lock
            // is the only time the parent has both browsers in front of them, so
            // it is the only time they can move bookmarks out of the browser they
            // are about to lose. A herald that appears after the lock is a herald
            // that appears after their bookmarks are gone.
            //
            // Nothing here enforces anything: no restriction is applied, no app
            // is removed. Installing what the policy *requires* is additive, and
            // it is the removals that wait for the parent to ask.
            fetchPolicyAndRequiredApps(context)
        }

        /**
         * Fetches the current policy and installs whatever it requires.
         *
         * Called at provisioning and again when the parent locks the phone. It
         * only ever *adds*: the restrictions, the filter and the app removals are
         * [DeviceOwnerManager]'s, and they still wait for the lock.
         */
        fun fetchPolicyAndRequiredApps(context: Context) {
            ProvisioningLog.record(context, "fetchPolicyAndRequiredApps: policy refresh + required apps")
            PolicyWorker.refreshNow(context)
            UpdateWorker.runNow(context)
        }
    }
}
