package app.drawbridge.policy.work

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
import app.drawbridge.policy.PolicyConfig
import app.drawbridge.policy.PolicyManager
import java.util.concurrent.TimeUnit

/** Fetches and applies the policy in the background. */
class PolicyWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val manager = PolicyManager.getInstance(applicationContext, PolicyConfig())
        manager.ensureLoaded()
        return when (val outcome = manager.refresh()) {
            is PolicyManager.RefreshOutcome.Success -> {
                Log.i(TAG, "Policy check complete at version ${outcome.version}")
                Result.success()
            }
            is PolicyManager.RefreshOutcome.Failure -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "PolicyWorker"

        private const val PERIODIC_WORK_NAME = "drawbridge-policy-poll"
        private const val ONE_SHOT_WORK_NAME = "drawbridge-policy-poll-now"

        /**
         * Schedules the daily poll.
         *
         * WorkManager alone is not dependable on OEMs that kill background jobs
         * aggressively (Xiaomi, Huawei, Oppo/Realme), so this is only one of three
         * paths to a refresh: the DPC's always-on foreground service also calls
         * [refreshNow] on network changes, and the user can force one from the UI.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PolicyWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            withWorkManager(context) {
                enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /** Requests an immediate check, e.g. after the network came back. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PolicyWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            withWorkManager(context) {
                enqueueUniqueWork(ONE_SHOT_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            withWorkManager(context) {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(ONE_SHOT_WORK_NAME)
            }
        }

        /**
         * Both apps schedule work from `Application.onCreate`, where an
         * uninitialised WorkManager would otherwise take the whole app down at
         * launch. Losing scheduled refreshes is bad; crash-looping and losing the
         * filter entirely is worse, so this logs and carries on with whatever
         * policy is already on disk.
         */
        private inline fun withWorkManager(context: Context, block: WorkManager.() -> Unit) {
            try {
                WorkManager.getInstance(context).block()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; policy will not refresh on a schedule", e)
            }
        }
    }
}
