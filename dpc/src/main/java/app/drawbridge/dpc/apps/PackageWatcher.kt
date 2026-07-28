package app.drawbridge.dpc.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches for newly installed apps and hands them to [AppBlocker].
 *
 * Two mechanisms, because neither alone is dependable:
 *
 *  - A **runtime-registered** receiver for `ACTION_PACKAGE_ADDED`, which fires
 *    within a moment of an install finishing. It must be registered in code:
 *    since Android 8 this is an implicit broadcast, and a manifest-declared
 *    receiver for it silently never runs.
 *  - A **periodic sweep** using [PackageManager.getChangedPackages], which
 *    catches anything installed while this process was not alive. Slower, but it
 *    does not depend on being running at the right moment.
 *
 * Lives inside the always-on VPN service because that is the longest-lived
 * process in the app.
 */
class PackageWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val blocker = AppBlocker(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sweepJob: Job? = null
    private var sequenceNumber = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
            // A replace is an update of something already present, which was
            // already evaluated when it was first installed.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

            val packageName = intent.data?.schemeSpecificPart ?: return
            scope.launch {
                val action = blocker.evaluate(packageName)
                if (action != AppBlocker.Action.NONE) {
                    Log.i(TAG, "Install of $packageName handled: $action")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        appContext.registerReceiver(receiver, filter)

        sweepJob = scope.launch {
            // An initial full sweep on start covers anything installed while the
            // service was down, including across a reboot.
            runCatching { blocker.sweep() }
                .onFailure { Log.e(TAG, "Initial package sweep failed", it) }

            while (isActive) {
                delay(SWEEP_INTERVAL_MILLIS)
                sweepChanged()
            }
        }
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
        sweepJob = null
        scope.cancel()
    }

    /**
     * Evaluates only the packages that changed since the last check, which is
     * far cheaper than re-examining every installed app.
     */
    private fun sweepChanged() {
        val changed = runCatching { appContext.packageManager.getChangedPackages(sequenceNumber) }
            .onFailure { Log.e(TAG, "Could not read changed packages", it) }
            .getOrNull() ?: return

        sequenceNumber = changed.sequenceNumber
        for (packageName in changed.packageNames) {
            runCatching { blocker.evaluate(packageName) }
                .onFailure { Log.e(TAG, "Could not evaluate $packageName", it) }
        }
    }

    private companion object {
        const val TAG = "PackageWatcher"
        const val SWEEP_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}
