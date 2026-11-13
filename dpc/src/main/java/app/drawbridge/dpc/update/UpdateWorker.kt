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
import app.drawbridge.dpc.admin.DeviceOwnerManager
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
        // These installs no longer wait for the lock, and the reversal is
        // deliberate. They used to, on the grounds that installing what the
        // policy requires is enforcement — but it is not: nothing is restricted
        // and nothing is removed, a browser is simply added. What made the wait
        // look necessary was the QR path, where this ran inside the setup wizard
        // and a ~470 MiB download competed with it; that path is retired.
        //
        // The window before the lock is now the only time the parent has both
        // their old browser and herald in front of them, which is when bookmarks
        // have to move across. Herald arriving after the lock is herald arriving
        // after the browser it should have inherited from is gone.
        //
        // Device Owner is still required, because these are silent
        // PackageInstaller sessions and nothing else could commit them.
        val requested = inputData.getBoolean(KEY_REQUESTED, false)
        val canInstall = DeviceOwnerManager(applicationContext).isDeviceOwner

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
            !canInstall -> {
                Log.i(TAG, "Not installing required apps: not device owner")
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

        // drawbridge installs its own update here again, unattended, from
        // 0.2.40. From 2026-08-10 to 2026-11 it could not: Play Protect refused
        // the install on any phone with a Google account, so this only logged
        // that an update was waiting and the parent started it from the Update
        // screen with Play Protect paused. After the appeal Play Protect allows
        // it (verified 2026-11-11 and 2026-11-13; see docs/handoff.md), and an
        // update somebody has to remember to press is an update most phones
        // never get.
        //
        // **Once per version, automatically.** A refusal surfaces only in
        // InstallResultReceiver, after this worker has returned, so it cannot
        // feed a retry here — and retrying a refused install every three hours
        // is what was switched off in August. A version this worker has handed
        // to the installer is not handed over again; the Update screen's button
        // still is, for a parent who wants to try. A failure *before* the
        // commit — no network, a bad checksum — never reached the installer and
        // is retried on the next poll.
        //
        // Last, after the browsers, because a successful install replaces this
        // process.
        if (canInstall) {
            installer.availableSelfUpdate()?.let { update ->
                val attempts = selfUpdateAttempts(applicationContext)
                if (attempts.getInt(KEY_ATTEMPTED_VERSION, 0) >= update.versionCode) {
                    Log.i(
                        TAG,
                        "drawbridge ${update.versionCode} was already tried unattended; " +
                            "leaving it to the Update screen",
                    )
                } else {
                    InstallOutcome(applicationContext).clear()
                    when (val result = installer.installSelfUpdate()) {
                        is AppInstaller.Result.Started -> {
                            attempts.edit().putInt(KEY_ATTEMPTED_VERSION, update.versionCode).commit()
                            Log.i(TAG, "Installing drawbridge ${update.versionCode} unattended")
                        }
                        is AppInstaller.Result.Failed ->
                            Log.e(TAG, "drawbridge ${update.versionCode} not installed: ${result.reason}")
                        is AppInstaller.Result.UpToDate -> Unit
                    }
                }
            }
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

        private const val ATTEMPTS_PREFS = "drawbridge_self_update"
        private const val KEY_ATTEMPTED_VERSION = "attempted_version"

        private fun selfUpdateAttempts(context: Context) =
            context.getSharedPreferences(ATTEMPTS_PREFS, Context.MODE_PRIVATE)

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
