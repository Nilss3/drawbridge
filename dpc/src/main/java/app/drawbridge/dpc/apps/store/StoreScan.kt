package app.drawbridge.dpc.apps.store

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.apps.AppBlocker
import kotlinx.coroutines.delay

/**
 * The catch-up pass itself, separated from the job that used to be its only home.
 *
 * ### Why this is not a worker any more
 *
 * **Measured on the owner's Moto on 2026-08-18, on a phone freshly installed with
 * build 36.** Diagnostics said `store rule: on`, `store to scan: 59`,
 * `store last fetch: (never)`, on unmetered Wi-Fi, with the job's own constraints
 * reported satisfied — and three minutes of logcat showed nothing running at all.
 * The scan had never fetched a single listing. What the log did show, at the
 * moment of installation and again five times that morning:
 *
 * ```
 * WM-WorkerWrapper: Work [ ... StoreScanWorker ] was cancelled
 * StoreScanWorker: Store scan: asking about 60 of 60 packages
 * StoreScanWorker: Store scan: asked about 0 of 60, 0 usable, 0 unverified (stopped early)
 * ```
 *
 * Two instances starting in the same millisecond, both stopped before the first
 * request. WorkManager cancels running work when the app is replaced — which is
 * exactly what installing drawbridge *is* — and the one-off scan and the periodic
 * rescan are two jobs carrying the same worker, so they collide as well. Between
 * the two, the pass that matters most (the first one, on a phone that has just
 * been set up) was the pass least likely to survive.
 *
 * **The work never needed a scheduler.** It runs inside
 * [app.drawbridge.dpc.vpn.DnsFilterService], which is an always-on VPN service and
 * therefore the longest-lived process this app has — the same reason
 * [app.drawbridge.dpc.apps.PackageWatcher] lives there. It has no deadline, no
 * charging requirement, and it is already resumable: the cache *is* the progress,
 * so a pass that dies with the process picks up where it left off at the next
 * start. What WorkManager was adding was a cancellation source, a ten-minute
 * limit and a backoff, in exchange for nothing.
 *
 * [StoreScanWorker] still exists for the fortnightly re-ask, where being deferred
 * is the whole point.
 */
object StoreScan {

    private const val TAG = "StoreScan"

    /**
     * Bounded per pass so a caller with a deadline — the worker — cannot approach
     * it. The in-service caller loops instead.
     */
    const val MAX_PER_PASS = 60

    /**
     * Deliberate, and small. This is one phone asking about its own apps rather
     * than a crawl, and pacing keeps that true: a burst of eighty requests looks
     * like something else regardless of intent.
     */
    private const val PACE_MILLIS = 250L

    /** What a pass did, so the caller can decide whether to come back. */
    data class Outcome(val asked: Int, val remaining: Int, val interrupted: Boolean)

    /**
     * Asks the store about as many outstanding packages as one pass allows.
     *
     * @param shouldStop polled between requests. The worker passes its own
     *   `isStopped`; the service passes nothing, because cancelling its scope is
     *   how it stops.
     */
    suspend fun runPass(context: Context, shouldStop: () -> Boolean = { false }): Outcome {
        // The rule is the document's, so a stale document means scanning against
        // thresholds that have moved — including a whitelist that may have grown
        // precisely because somebody lost an app.
        DrawbridgeApplication.policy(context).ensureLoaded()

        val blocker = AppBlocker(context)
        val wanted = blocker.packagesWantingStoreAnswer()
        if (wanted.isEmpty()) {
            Log.i(TAG, "Store scan: nothing to ask about")
            return Outcome(asked = 0, remaining = 0, interrupted = false)
        }

        val batch = wanted.take(MAX_PER_PASS)
        Log.i(TAG, "Store scan: asking about ${batch.size} of ${wanted.size} packages")

        var asked = 0
        var interrupted = false
        for (packageName in batch) {
            if (shouldStop()) {
                interrupted = true
                break
            }
            runCatching { blocker.ensureStoreAnswer(packageName) }
                .onFailure { Log.w(TAG, "Store scan could not ask about $packageName", it) }
            asked++
            delay(PACE_MILLIS)
        }

        val stats = StoreCatalogue(context).stats()
        Log.i(
            TAG,
            "Store scan: asked about $asked of ${wanted.size}, " +
                "${stats.usable} usable, ${stats.failed} unverified" +
                if (interrupted) " (stopped early)" else "",
        )

        // Now the answers can act. Without this the scan would populate a cache
        // and change nothing until the next fifteen-minute sweep — which is fine,
        // but a phone that has just been set up is entitled to change now.
        runCatching { blocker.sweep() }
            .onFailure { Log.e(TAG, "Sweep after the store scan failed", it) }

        return Outcome(
            asked = asked,
            remaining = (wanted.size - asked).coerceAtLeast(0),
            interrupted = interrupted,
        )
    }

    /**
     * Keeps going until there is nothing left to ask, for a caller that outlives
     * a single pass.
     *
     * Stops when a pass makes no progress, which covers the case that used to be
     * invisible: every remaining package failing means the network is not really
     * there, and asking the same sixty again immediately would be a loop rather
     * than a retry. A failure is cached for an hour, so the next sweep's pass
     * picks them up.
     */
    suspend fun runToCompletion(context: Context) {
        while (true) {
            val outcome = runPass(context)
            if (outcome.remaining == 0 || outcome.asked == 0) return
        }
    }

    /**
     * True when the phone is on a connection somebody pays for by the megabyte.
     *
     * The scan is 50–100 MB, so it belongs on Wi-Fi — but the *decision* is made
     * here rather than by a WorkManager constraint, for the reason this whole file
     * exists. Treated as metered when it cannot be determined: the fallback should
     * be the one that spends nothing.
     */
    fun onMeteredNetwork(context: Context): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }.getOrDefault(true)
}
