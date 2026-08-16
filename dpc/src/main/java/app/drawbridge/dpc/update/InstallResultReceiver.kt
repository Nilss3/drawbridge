package app.drawbridge.dpc.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.apps.InstallLockSettings

/** Reports the outcome of a silent install started by [AppInstaller]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: "(unknown)"
        val version = intent.getIntExtra(EXTRA_VERSION_CODE, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        // Written down before it is logged, because this is the only place the
        // answer exists and the screen that asked the question is usually gone
        // by now. See InstallOutcome.
        InstallOutcome(context).record(
            packageName = packageName,
            versionCode = version,
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )

        // Closes the window AppInstaller opened in the install restriction —
        // whatever the verdict, and *before* the branches below, because a
        // failure has to put the phone back exactly as much as a success does.
        // STATUS_PENDING_USER_ACTION is terminal for our purposes too: a Device
        // Owner that is being asked to confirm an install is no longer going to
        // complete one silently.
        //
        // Deliberately not a `finally` back in AppInstaller: `commit` returns
        // long before the package lands, so restoring the restriction there
        // would re-block the session it was lifted for. See
        // DeviceOwnerManager.allowOwnInstalls.
        DeviceOwnerManager(context).ownInstallFinished(packageName)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // Into the install lock's closed set a second time. AppInstaller
                // already put it there before committing, to beat
                // ACTION_PACKAGE_ADDED; this covers the other direction, where a
                // parent locks the phone while a 230 MB herald is still coming
                // down and the lock re-takes the snapshot underneath it.
                InstallLockSettings(context).allow(packageName)
                Log.i(TAG, "Installed $packageName version $version")
            }

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
