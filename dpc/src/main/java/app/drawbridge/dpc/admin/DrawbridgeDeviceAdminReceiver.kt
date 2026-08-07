package app.drawbridge.dpc.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication

/**
 * The component `dpm set-device-owner` (or QR provisioning) elevates to Device
 * Owner. Its name is part of the provisioning command and of the QR payload, so
 * it must not be renamed after any device has been provisioned.
 */
class DrawbridgeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
        ProvisioningLog.record(context, "onEnabled")
        DrawbridgeApplication.onAdminEnabled(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    /**
     * Fired at the end of QR / NFC provisioning. Applying the restriction set
     * here means a QR-provisioned device is locked down before it ever reaches
     * the home screen.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "Provisioning complete")
        ProvisioningLog.record(context, "onProfileProvisioningComplete")
        DeviceOwnerManager(context).applyManagedDevicePolicy()
        DrawbridgeApplication.onAdminEnabled(context)
    }

    override fun onReadyForUserInitialization(context: Context, intent: Intent) {
        Log.i(TAG, "Ready for user initialization")
    }

    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        Log.i(TAG, "Ownership transfer complete")
        DeviceOwnerManager(context).applyManagedDevicePolicy()
    }

    companion object {
        private const val TAG = "DrawbridgeAdmin"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, DrawbridgeDeviceAdminReceiver::class.java)
    }
}
