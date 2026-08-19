package app.drawbridge.dpc.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication

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

        // A boundary is either the start of a curfew or the end of one, and this
        // receiver only ever fires at one. Asking for a policy refresh on both is
        // deliberate: the one that matters is the morning, where the phone has
        // been offline for hours and its blocklists are that many hours stale,
        // and the evening call costs a request that fails immediately. Tracking
        // which kind of boundary this was would mean keeping state, which is the
        // one thing this design does not do.
        DrawbridgeApplication.fetchPolicyAndRequiredApps(context)
    }

    private companion object {
        const val TAG = "CurfewReceiver"
    }
}
