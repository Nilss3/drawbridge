package app.drawbridge.dpc.apps.store

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Asks the store about every app already on the phone, once, and then keeps the
 * answers from going stale.
 *
 * ### Why a full pass exists at all
 *
 * [app.drawbridge.dpc.apps.PackageWatcher] covers apps as they *arrive*, which
 * is the wrong half on its own: **the phone arrives with the problem already on
 * it.** A rule that only ever applied to what was installed after drawbridge was
 * set up would leave every game and every companion app that was there first,
 * which is most of them on a phone somebody is installing this to fix.
 *
 * ### Why it waits for Wi-Fi
 *
 * A listing page is ~1.2 MB and the fields sit 85–91% of the way into it, so a
 * `Range` request saves nothing — measured 2026-08-17. Forty to eighty
 * user-installed apps is **50–100 MB**, which is not something to spend on
 * somebody's mobile data because they happened to press Lock in a car.
 *
 * **The constraint is on the request rather than checked in [doWork]**, which is
 * the opposite of [app.drawbridge.dpc.update.UpdateWorker] and deliberately so.
 * That worker carries a mixed payload — a 3 MB self-update that must reach a
 * phone which never sees Wi-Fi, and a 235 MB browser that must not — so it takes
 * the loose constraint and splits the decision inside. Here the whole job is the
 * expensive half, so WorkManager can simply hold it until the network is right,
 * which it does better than a poll would.
 *
 * Until it runs, the unscanned apps are `unverified`, which means *keep*, and
 * Diagnostics reports how many. Enforcement arriving late is the cost; removing
 * apps over a metered link would be the alternative.
 *
 * ### It resumes rather than restarts
 *
 * Nothing tracks progress, because the cache already is the progress: a package
 * with a current answer is skipped by
 * [AppBlocker.packagesWantingStoreAnswer]. A run that is cut short by the
 * network, by Doze or by the ten-minute execution limit leaves everything it
 * managed behind it, and the next run picks up the rest.
 */
class StoreScanWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val outcome = StoreScan.runPass(applicationContext) { isStopped }

        // **An interrupted run must not report success**, or the phone would wait
        // for the periodic pass while believing it had finished — a scan that
        // stopped and a scan that completed would be indistinguishable, which is
        // the shape of silent failure this project keeps paying for. `retry` is
        // also how a batch-sized run says "there is more": the cache is the
        // bookmark, so coming back is a continuation rather than a repeat.
        return if (outcome.interrupted || outcome.remaining > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val TAG = "StoreScanWorker"
        private const val WORK_NAME = "drawbridge-store-scan"
        private const val PERIODIC_WORK_NAME = "drawbridge-store-rescan"

        /**
         * Fortnightly, and the reason is re-rating rather than drift.
         *
         * An answer going stale is harmless on its own: an app that survived the
         * first pass was *allowed*, and an expired entry reads as `unverified`,
         * which is also keep. What this catches is the other direction — a
         * publisher who re-rates an app upward after it is already on the phone,
         * which no other signal on the device would ever reveal. Only expired
         * entries cost a request, so a quiet fortnight is a job that finds
         * nothing to do and stops.
         *
         * **This interval is not what the scan costs**, which is worth stating
         * because it is the obvious place to look and the wrong one:
         * `StoreCatalogue`'s TTL decides how often an entry becomes worth
         * re-asking, and this only decides how soon after that somebody notices.
         * It went from seven days to fourteen on 2026-08-17 alongside a TTL that
         * went from one month to six; the halved wake-ups are tidiness, the TTL
         * is the 0.6–1.2 GB a year.
         */
        private const val RESCAN_DAYS = 14L

        private val unmetered = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /**
         * Starts the catch-up pass. Called at the lock, which is when the rule
         * starts applying and therefore when the phone first needs answers.
         *
         * `KEEP` rather than `REPLACE`, unlike `UpdateWorker.runNow`: that one
         * has to mean *now* because a parent is standing over the phone waiting
         * for a browser to appear. This one is a background catch-up nobody is
         * watching, and replacing a run already in progress would discard its
         * place in the queue to start the same work again.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<StoreScanWorker>()
                .setConstraints(unmetered)
                .build()
            try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; the store scan cannot be queued", e)
            }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StoreScanWorker>(RESCAN_DAYS, TimeUnit.DAYS)
                .setConstraints(unmetered)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager is unavailable; the store rescan is not scheduled", e)
            }
        }
    }
}
