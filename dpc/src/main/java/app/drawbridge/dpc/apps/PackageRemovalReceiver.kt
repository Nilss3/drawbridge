package app.drawbridge.dpc.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
                // **Success is not the same as gone.** Uninstalling an app that
                // shipped with the phone and was later updated removes the
                // *update* and leaves the factory build installed, and the
                // session reports success either way. From here that reads as a
                // package removed; on the phone it is an app still in the
                // launcher, which is exactly what an app surviving a lock looks
                // like.
                //
                // Re-evaluating settles it rather than trusting the status: what
                // remains is a system package, so the second pass hides it
                // instead of uninstalling it, and hiding is synchronous and
                // cannot come back here. Nothing loops.
                if (isInstalled(context, packageName)) {
                    Log.w(TAG, "$packageName survived its uninstall; re-evaluating to hide it")
                    AppBlocker(context).evaluate(packageName)
                } else {
                    Log.i(TAG, "Uninstalled $packageName")
                }

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

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
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
