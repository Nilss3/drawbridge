package app.drawbridge.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.vpn.DnsFilterService

/**
 * Re-asserts the policy after a reboot.
 *
 * The always-on VPN is started by the platform on a managed device, so this is a
 * backstop rather than the primary path — it matters on devices where the app is
 * installed without Device Owner, and it re-applies restrictions that an OS
 * upgrade may have dropped.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        Log.i(TAG, "Boot completed; re-applying policy")
        DeviceOwnerManager(context).applyManagedDevicePolicy()

        // Returns a consent intent when the app is not Device Owner, which cannot
        // be shown from a receiver; in that case the parent starts it from the
        // status screen instead.
        DnsFilterService.requestStart(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
