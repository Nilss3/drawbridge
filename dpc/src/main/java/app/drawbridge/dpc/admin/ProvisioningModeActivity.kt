package app.drawbridge.dpc.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Answers the platform's question "which mode do you want to be provisioned in?"
 *
 * Since Android 11 a DPC that the *platform* provisions — QR, NFC or cloud
 * enrolment — must declare this activity. The setup wizard launches it after the
 * APK is downloaded and its signature verified, and waits for the answer before
 * granting Device Owner. With no handler the flow has nothing to hand off to and
 * dies with *"Something went wrong"*, immediately after showing "this device
 * belongs to your organization" — which is exactly what a Moto G15 did on
 * 2026-08-07, and had been misread as Play Protect's DPC allowlist.
 *
 * **`dpm set-device-owner` never comes through here.** It grants Device Owner
 * directly and asks nothing, which is why every emulator run and every adb
 * provisioning passed while the QR path had never once worked.
 *
 * The answer is always "fully managed device". drawbridge filters DNS for the
 * whole device through an always-on VPN and removes apps anywhere on it; inside
 * a work profile it could do neither, so a managed profile is not a degraded
 * mode of this app but a broken one. If the platform will not offer a fully
 * managed device we fail provisioning rather than provision something that
 * cannot do the job.
 *
 * Exported with no permission, deliberately: the activity confers nothing. It
 * writes a constant into an ActivityResult and finishes, so an app that launched
 * it would learn only what this source file already says.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Present from Android 12; absent on 11, where fully managed is the only
        // thing this activity is ever launched for. A missing extra therefore
        // means "no constraint stated" rather than "nothing allowed".
        val allowed = intent
            ?.getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
            )

        if (allowed != null &&
            !allowed.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        ) {
            Log.e(TAG, "Fully managed device not offered (allowed modes: $allowed); refusing")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        Log.i(TAG, "Requesting provisioning as a fully managed device")
        setResult(
            RESULT_OK,
            Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            )
        )
        finish()
    }

    private companion object {
        private const val TAG = "DrawbridgeProvisioning"
    }
}
