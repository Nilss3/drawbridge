package app.drawbridge.dpc.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.security.ParentKey
import java.util.concurrent.TimeUnit

/** Applies app updates named by the signed policy document. */
class UpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // Policy first: the update is described by the policy, so checking for an
        // update against a stale copy would just install the version we already
        // have.
        DrawbridgeApplication.policy(applicationContext).let { policy ->
            policy.ensureLoaded()
            policy.refresh()
        }

        val installer = AppInstaller(applicationContext)

        // Required apps first: a device missing herald has no browser at all,
        // which matters more than drawbridge being a version behind.
        //
        // But not before the phone has been locked. Installing what the policy
        // requires is enforcement, and enforcement waits for the parent to ask —
        // otherwise a provisioned-but-unlocked phone quietly pulls down ~470 MiB
        // of browsers on its next daily run, which is exactly the surprise the
        // rest of the deferral exists to avoid.
        //
        // [requested] rather than a plain protectedSince check, because locking
        // calls runNow *before* it mints the key: the flag is what distinguishes
        // "the parent just asked for this" from "the daily poll came round", and
        // avoids a race with an ordering that is correct for other reasons.
        val requested = inputData.getBoolean(KEY_REQUESTED, false)
        val protectedPhone = ParentKey(applicationContext).protectedSince > 0L

        // The browsers are ~235 MiB each, so they wait for an unmetered network
        // -- but drawbridge's own update, a few lines down, does not. That split
        // is the point: the periodic job now runs on any connection so a phone
        // that never sees Wi-Fi still gets fixes, while nothing large is pulled
        // over a metered link that somebody pays for by the megabyte.
        //
        // An explicitly requested run ignores this. Locking is the parent
        // standing over the device waiting for herald to appear, and a phone with
        // no browser at all is worse than a download they chose to start.
        val unmetered = isUnmetered()
        val required = when {
            !requested && !protectedPhone -> {
                Log.i(TAG, "Not installing required apps: phone has never been locked")
                emptyMap()
            }
            !requested && !unmetered -> {
                Log.i(TAG, "Not installing required apps: network is metered")
                emptyMap()
            }
            else -> installer.installMissingRequiredApps()
        }
        required.forEach { (packageName, outcome) ->
            if (outcome is AppInstaller.Result.Failed) {
                Log.e(TAG, "Could not install $packageName: ${outcome.reason}")
            }
        }

        // drawbridge does *not* install its own update here any more, and that is
        // a concession rather than a design. Play Protect refuses it on any
        // phone with a Google account -- five rounds of experiment moved the
        // manifest, the permissions, the install session and finally the package
        // name, and only the rename got through, which is not a fix. Retrying
        // silently every three hours only fails silently every three hours.
        //
        // herald above is untouched: its installs are not refused, and a phone
        // with no browser is a different order of problem.
        installer.availableSelfUpdate()?.let { update ->
            Log.i(
                TAG,
                "drawbridge ${update.versionCode} is available; " +
                    "waiting for the parent to start it from the app",
            )
        }

        val failed = required.values.any { it is AppInstaller.Result.Failed }
        return if (failed) Result.retry() else Result.success()
    }

    private fun isUnmetered(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "drawbridge-self-update"
        private const val IMMEDIATE_WORK_NAME = "drawbridge-install-now"

        /** Marks a run the parent asked for, as opposed to the daily poll. */
        private const val KEY_REQUESTED = "requested"

        /**
         * Three hours, not a day.
         *
         * A day made the system untestable: a fix pushed in the morning could
         * not be confirmed on a device until the next day, and on 2026-08-08 a
         * phone sat for over 24 hours without picking up either a new policy or
         * a new build. It also costs almost nothing -- the pinned lists only
         * download when their hash changes, and the unpinned ones have their own
         * 24-hour age gate in PolicyStore, so a poll is one ~19 KB signed
         * document.
         */
        private const val POLL_HOURS = 3L

        /**
         * Runs the installer now. Called when the parent locks the phone, which
         * is the moment they are standing over it waiting for herald to appear.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(KEY_REQUESTED to true))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            try {
                // REPLACE, not KEEP: a previous attempt that failed — no network
                // at provisioning, say — sits in exponential backoff, and KEEP
                // would make every later trigger a silent no-op until that
                // backoff elapsed. An explicit "install now" has to mean now.
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; cannot install required apps", e)
            }
        }

        fun schedule(context: Context) {
            // Only a connection, not an unmetered one. drawbridge's own update is
            // ~3 MB and has to be able to reach a phone that is rarely or never
            // on Wi-Fi -- a child's phone, or anyone living deliberately without
            // one -- and a curfew takes the device off the network for hours at a
            // time besides. The expensive half is gated separately in doWork.
            //
            // requiresBatteryNotLow is gone for the same reason: it is one more
            // condition that can silently hold back a 3 MB download indefinitely.
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(POLL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // UPDATE, not KEEP -- see PolicyWorker for why.
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; self-update is disabled", e)
            }
        }
    }
}
