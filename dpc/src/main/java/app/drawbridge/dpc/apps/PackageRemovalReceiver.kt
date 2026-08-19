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
                if (isInstalled(context, packageName)) {
                    fallBack(context, packageName, "survived its uninstall")
                } else {
                    Log.i(TAG, "Uninstalled $packageName")
                }

            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // Should not happen for a Device Owner. If it does, the silent
                // path is unavailable — so rather than pop a dialog at whoever
                // is holding the phone, or leave the app usable, take the route
                // that needs nobody's consent.
                fallBack(context, packageName, "unexpectedly needs user action to uninstall")

            else -> {
                Log.e(
                    TAG,
                    "Uninstall of $packageName failed with status $status: " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
                fallBack(context, packageName, "could not be uninstalled")
            }
        }
    }

    /**
     * The app is still on the phone, so hide or suspend it instead.
     *
     * **This is the half of the 2026-08-14 fallback that was missing.** That day's
     * work made a *hide* the platform refused fall through to suspension, which
     * fixed the YouTube app on the Moto — and left the mirror image untouched
     * here, where an uninstall the platform refused was written to the log and
     * forgotten. A user-installed app that cannot be uninstalled stayed fully
     * usable on a locked phone, silently and for good, because the only thing
     * watching was a `Log.e` on a device with no adb. Every branch out of
     * [AppBlocker.remove] now ends at the same ladder.
     *
     * **[AppBlocker.hideOrSuspend] rather than [AppBlocker.evaluate], and that is
     * the load-bearing part.** Re-evaluating a user-installed app would decide to
     * uninstall it, be refused, and arrive back here — every failure becoming an
     * endless retry. This ladder never issues an uninstall, so it cannot come
     * back. The policy check that re-evaluating would have brought with it is
     * kept explicitly, since the seconds between a session starting and failing
     * are enough for a parent to have switched the option back on.
     */
    private fun fallBack(context: Context, packageName: String, what: String) {
        val blocker = AppBlocker(context)
        if (!blocker.disallows(packageName)) {
            Log.i(TAG, "$packageName $what, and policy now allows it; leaving it alone")
            return
        }
        Log.w(TAG, "$packageName $what; hiding or suspending it instead")
        blocker.hideOrSuspend(packageName)
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
