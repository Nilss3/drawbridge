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
import app.drawbridge.dpc.DrawbridgeApplication
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
        val required = installer.installMissingRequiredApps()
        required.forEach { (packageName, outcome) ->
            if (outcome is AppInstaller.Result.Failed) {
                Log.e(TAG, "Could not install $packageName: ${outcome.reason}")
            }
        }

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

        /**
         * Runs the installer now. Called right after provisioning, where the
         * parent is standing over the device waiting for herald to appear.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
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
