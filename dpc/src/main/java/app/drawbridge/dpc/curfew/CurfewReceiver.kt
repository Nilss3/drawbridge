package app.drawbridge.dpc.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication

/**
 * Fires at each curfew boundary and hands back to [CurfewController].
 *
 * **Not yet registered in the manifest** — see [CurfewController]. Adding the
 * `<receiver>` entry is part of turning the feature on.
 *
 * Reads the policy rather than trusting the alarm: an alarm only says "the state
 * may have changed", and the policy may have been replaced since it was set.
 */
class CurfewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Curfew boundary reached")
        val policy = DrawbridgeApplication.policy(context).policy.value
        CurfewController(context).apply(policy.curfew)
    }

    private companion object {
        const val TAG = "CurfewReceiver"
    }
}
