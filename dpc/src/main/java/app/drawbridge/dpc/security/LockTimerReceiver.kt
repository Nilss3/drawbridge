package app.drawbridge.dpc.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires at the deadline and hands back to [LockTimerController].
 *
 * The alarm is only a hint that the moment may have come. The controller
 * recomputes from the stored deadline and the clock, so an alarm that survived a
 * cancelled timer — or one that arrives early because Doze rescheduled it — costs
 * one redundant evaluation rather than an unlock nobody asked for.
 */
class LockTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Lock timer alarm")
        LockTimerController(context).apply()
    }

    private companion object {
        const val TAG = "LockTimerReceiver"
    }
}
