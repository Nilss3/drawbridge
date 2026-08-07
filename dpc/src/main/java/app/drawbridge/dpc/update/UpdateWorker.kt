package app.drawbridge.dpc.update

import android.content.Context
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

        val required = if (requested || protectedPhone) {
            installer.installMissingRequiredApps()
        } else {
            Log.i(TAG, "Not installing required apps: phone has never been locked")
            emptyMap()
        }
        required.forEach { (packageName, outcome) ->
            if (outcome is AppInstaller.Result.Failed) {
                Log.e(TAG, "Could not install $packageName: ${outcome.reason}")
            }
        }

        // Deliberately not gated. Keeping *itself* current is not something
        // drawbridge does to the user, and a fix has to be able to reach an idle
        // provisioned phone whose parent has not locked it yet.
        val self = installer.checkAndInstallSelf()
        if (self is AppInstaller.Result.Failed) {
            Log.e(TAG, "Self-update failed: ${self.reason}")
        }

        val failed = required.values.any { it is AppInstaller.Result.Failed } ||
            self is AppInstaller.Result.Failed
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "drawbridge-self-update"
        private const val IMMEDIATE_WORK_NAME = "drawbridge-install-now"

        /** Marks a run the parent asked for, as opposed to the daily poll. */
        private const val KEY_REQUESTED = "requested"

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
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; self-update is disabled", e)
            }
        }
    }
}
