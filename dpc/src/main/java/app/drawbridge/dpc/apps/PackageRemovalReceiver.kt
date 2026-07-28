package app.drawbridge.dpc.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the result of a silent uninstall.
 *
 * As Device Owner the uninstall completes with no confirmation dialog, so the
 * only thing this has to do is report failures — but it has to exist, because
 * `PackageInstaller.uninstall` requires somewhere to send its status.
 */
class PackageRemovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: "(unknown)"

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Uninstalled $packageName")

            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // Should not happen for a Device Owner. If it does, the silent
                // path is unavailable and the app stays installed rather than
                // popping a dialog at whoever is holding the phone.
                Log.w(TAG, "Uninstall of $packageName unexpectedly needs user action")

            else -> Log.e(
                TAG,
                "Uninstall of $packageName failed with status $status: " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }

    companion object {
        private const val TAG = "PackageRemoval"
        private const val EXTRA_TARGET_PACKAGE = "app.drawbridge.dpc.TARGET_PACKAGE"

        fun pendingIntent(context: Context, packageName: String): PendingIntent {
            val intent = Intent(context, PackageRemovalReceiver::class.java)
                .putExtra(EXTRA_TARGET_PACKAGE, packageName)
            return PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
