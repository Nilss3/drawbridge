package app.drawbridge.dpc.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires at each curfew boundary and hands back to [CurfewController].
 *
 * Reads the settings rather than trusting the alarm: an alarm only says "the
 * state may have changed", and the parent may have changed the schedule — or the
 * philosophy entirely — since it was set. [CurfewController.apply] recomputes
 * from the clock, so a stale alarm costs one redundant evaluation rather than a
 * wrong state.
 */
class CurfewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Curfew boundary reached")
        CurfewController(context).apply()
    }

    private companion object {
        const val TAG = "CurfewReceiver"
    }
}
