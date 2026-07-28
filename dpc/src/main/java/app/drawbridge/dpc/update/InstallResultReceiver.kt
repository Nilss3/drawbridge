package app.drawbridge.dpc.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Reports the outcome of a silent install started by [AppInstaller]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: "(unknown)"
        val version = intent.getIntExtra(EXTRA_VERSION_CODE, -1)

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Installed $packageName version $version")

            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // A Device Owner installs silently. Reaching this means the app
                // is no longer Device Owner, and prompting whoever is holding
                // the phone to approve an install is not something to do
                // silently.
                Log.e(TAG, "Install of $packageName needs user approval; not device owner?")

            else -> Log.e(
                TAG,
                "Install of $packageName failed with status $status: " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }

    companion object {
        private const val TAG = "InstallResult"
        private const val EXTRA_PACKAGE = "app.drawbridge.dpc.PACKAGE"
        private const val EXTRA_VERSION_CODE = "app.drawbridge.dpc.VERSION_CODE"

        fun pendingIntent(context: Context, packageName: String, versionCode: Int): PendingIntent {
            val intent = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_VERSION_CODE, versionCode)
            return PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
