package app.drawbridge.dpc.curfew

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Re-evaluates connectivity on a timer, because an alarm that does not fire
 * leaves the phone dark.
 *
 * **This exists because of the failure direction, not for tidiness.** Going into
 * a curfew and failing is a phone that stays online: annoying, visible, and
 * fixed at the next boundary. Coming *out* of one and failing is a phone with no
 * internet and no way to hear that it should have some — the exact state
 * everything else here is designed against, and the one reported from the
 * reference phone on 2026-08-12, where the evening boundary worked and the
 * morning one did not.
 *
 * The alarm remains the primary mechanism: it is punctual, and this is not.
 * `setAndAllowWhileIdle` can be deferred by Doze, a pending intent can be lost
 * to a crash or an OS upgrade, and neither survives being the only mechanism.
 *
 * **Deliberately unconstrained.** Every other worker in this app waits for a
 * network, which is exactly wrong here: the state this needs to repair is one
 * where there is no network, so a `NetworkType.CONNECTED` constraint would keep
 * it from ever running on the phone that needs it most. It does no I/O.
 *
 * Fifteen minutes is WorkManager's floor for periodic work. A curfew that lifts
 * up to a quarter of an hour late is a poor experience; a curfew that never
 * lifts is a broken phone, and this is the difference between them.
 */
class CurfewWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // apply() recomputes from the clock and re-arms the alarm, so a run that
        // finds nothing wrong still repairs a lost alarm.
        runCatching { CurfewController(applicationContext).apply() }
            .onFailure { Log.e(TAG, "Curfew re-evaluation failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "CurfewWorker"
        private const val WORK_NAME = "drawbridge-curfew"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP rather than UPDATE: the schedule never changes, and
                // replacing it on every process start would reset the interval
                // each time the app is opened.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CurfewWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
