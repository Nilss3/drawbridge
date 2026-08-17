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
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.apps.AppBlocker
import kotlinx.coroutines.delay
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
        // The rule is the document's, so a stale document means scanning against
        // thresholds that have moved — including a whitelist that may have grown
        // precisely because somebody lost an app.
        DrawbridgeApplication.policy(applicationContext).ensureLoaded()

        val blocker = AppBlocker(applicationContext)
        val wanted = blocker.packagesWantingStoreAnswer()
        if (wanted.isEmpty()) {
            Log.i(TAG, "Store scan: nothing to ask about")
            return Result.success()
        }

        val batch = wanted.take(MAX_PER_RUN)
        Log.i(TAG, "Store scan: asking about ${batch.size} of ${wanted.size} packages")

        var asked = 0
        var interrupted = false
        for (packageName in batch) {
            // Doze, a network change, or the ten-minute limit. Whatever the
            // reason, stop asking rather than finish a batch nobody is waiting
            // for any more.
            if (isStopped) {
                interrupted = true
                break
            }
            runCatching { blocker.ensureStoreAnswer(packageName) }
                .onFailure { Log.w(TAG, "Store scan could not ask about $packageName", it) }
            asked++
            // Deliberate, and small. This is one phone asking about its own apps
            // rather than a crawl, and pacing it keeps that true — a burst of
            // eighty requests looks like something else regardless of intent.
            // The job is already deferred to Wi-Fi, so the seconds cost nothing.
            delay(PACE_MILLIS)
        }

        val stats = StoreCatalogue(applicationContext).stats()
        Log.i(
            TAG,
            "Store scan: asked about $asked of ${wanted.size}, " +
                "${stats.usable} usable, ${stats.failed} unverified" +
                if (interrupted) " (stopped early)" else "",
        )

        // Now the answers can act. Without this the scan would populate a cache
        // and change nothing until the next fifteen-minute sweep — which is
        // fine, but a parent who locked the phone is entitled to see it happen.
        // Worth doing even on an interrupted run: what was asked about is real.
        runCatching { blocker.sweep() }
            .onFailure { Log.e(TAG, "Sweep after the store scan failed", it) }

        // **An interrupted run must not report success**, or the phone would
        // wait a week for the periodic pass while believing it had finished —
        // a scan that stopped and a scan that completed would be
        // indistinguishable, which is the shape of silent failure this project
        // keeps paying for. `retry` is also how a batch-sized run says "there is
        // more": the cache is the bookmark, so coming back is a continuation
        // rather than a repeat.
        return if (interrupted || wanted.size > batch.size) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "StoreScanWorker"
        private const val WORK_NAME = "drawbridge-store-scan"
        private const val PERIODIC_WORK_NAME = "drawbridge-store-rescan"

        /**
         * Bounded so one run cannot approach WorkManager's ten-minute limit on a
         * slow connection. Sixty listings is ~72 MB, which is already generous
         * for a single pass.
         */
        private const val MAX_PER_RUN = 60

        private const val PACE_MILLIS = 250L

        /**
         * Weekly, and the reason is re-rating rather than drift.
         *
         * An answer going stale is harmless on its own: an app that survived the
         * first pass was *allowed*, and an expired entry reads as `unverified`,
         * which is also keep. What this catches is the other direction — a
         * publisher who re-rates an app upward after it is already on the phone,
         * which no other signal on the device would ever reveal. Only expired
         * entries cost a request, so a quiet week is a job that finds nothing to
         * do and stops.
         */
        private const val RESCAN_DAYS = 7L

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
