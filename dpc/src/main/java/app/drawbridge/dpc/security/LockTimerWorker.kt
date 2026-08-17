package app.drawbridge.dpc.security

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Checks the deadline on a timer, because an alarm that does not fire leaves a
 * phone locked with a key that may not exist.
 *
 * **This exists because of the failure direction**, exactly as [
 * app.drawbridge.dpc.curfew.CurfewWorker] does. A missed curfew boundary is a
 * phone that is online when it should not be, visible and fixed at the next
 * boundary. A missed unlock is a phone nobody can open, and the timer *is* the
 * remedy for that state — so it cannot be the thing that depends on a single
 * pending intent surviving a fortnight of Doze, an app upgrade and whatever an
 * OEM's task killer does at 3am.
 *
 * The alarm remains the primary mechanism; this is the backstop, and hourly is
 * the compromise it needs to be. The shortest timer on offer is two hours, so an
 * unlock that falls back to this is at worst half of the shortest period late, and
 * only in the case where the punctual mechanism was already lost. It reads two
 * numbers out of a preference file and does no I/O at all.
 *
 * **It has a second job, which is why hourly is not extravagant.** The keyguard
 * counts down in words — *"drawbridge unlocks in 3 days"* — and that is a stored
 * string nothing re-resolves on its own. Each run rewrites it, so the sentence a
 * parent reads is at worst an hour behind.
 *
 * **Deliberately unconstrained**, for the same reason the curfew's is: the state
 * this repairs may well be a phone with no network — an always-offline lock
 * waiting to lift — so a `NetworkType.CONNECTED` constraint would keep it from
 * running on precisely the phone that needs it.
 */
class LockTimerWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // apply() re-arms the alarm as well as firing when due, so a run that
        // finds the deadline still ahead repairs a lost alarm on the way past.
        runCatching { LockTimerController(applicationContext).apply() }
            .onFailure { Log.e(TAG, "Lock timer check failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "LockTimerWorker"
        private const val WORK_NAME = "drawbridge-lock-timer"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP rather than UPDATE: the interval never changes, and
                // replacing the request on every process start would push the
                // next run an hour out each time drawbridge was opened.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LockTimerWorker>(1, TimeUnit.HOURS).build(),
            )
        }
    }
}
