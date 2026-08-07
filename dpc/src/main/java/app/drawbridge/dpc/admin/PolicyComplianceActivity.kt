package app.drawbridge.dpc.admin

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The platform's last step of QR / NFC provisioning: "you are Device Owner now —
 * make the device compliant, and tell me when it is."
 *
 * Required alongside [ProvisioningModeActivity] since Android 11. Provisioning
 * blocks here until this returns, and a DPC that never answers leaves the setup
 * wizard stuck on a device it has already elevated.
 *
 * There is nothing to do. [DrawbridgeDeviceAdminReceiver.onProfileProvisioningComplete]
 * has already applied the restriction set and started the filter by the time
 * this runs, which is what makes a QR-provisioned device locked down before it
 * reaches the home screen. So this reports compliance and gets out of the way.
 *
 * It deliberately does **not** open the configuration screen. Picking a policy
 * and locking is the parent's job, done deliberately from the launcher with the
 * phone in hand — not something to be walked through at the tail of a setup
 * wizard, where the key drawbridge shows exactly once would land in the middle
 * of someone else's flow.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProvisioningLog.record(this, "ADMIN_POLICY_COMPLIANCE received")
        Log.i(TAG, "Reporting policy compliance; provisioning can finish")
        ProvisioningLog.record(this, "ADMIN_POLICY_COMPLIANCE -> RESULT_OK")
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        private const val TAG = "DrawbridgeProvisioning"
    }
}
