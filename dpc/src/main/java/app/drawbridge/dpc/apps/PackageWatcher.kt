package app.drawbridge.dpc.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.apps.store.StoreScan
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
 *
 * **Both wait for a policy to have been read**, which matters because of the
 * shape of the periodic pass: it asks the platform which packages *changed*, so
 * anything judged wrongly — or skipped — on the way past is not revisited. A
 * decision made against an unread document does not get a second look until the
 * process restarts. See [start].
 *
 * A third mechanism exists for the case those two cannot see at all: a package
 * that was judged correctly, and then the *document* changed underneath it. See
 * [sweepOnNewPolicy].
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

            // **Updates are evaluated too, and used to be skipped.** The reason
            // for skipping them was that a replace is an update of something
            // already present, which was evaluated when it was first installed —
            // true, and it assumes the evaluation *stuck*. That is the assumption
            // this class keeps being wrong about. An OEM preload service that
            // reinstalls a blocked app hands it back with its hidden flag
            // cleared, and this is the broadcast that says so; skipping it left
            // the app usable until the fifteen-minute sweep noticed. It is what
            // the Moto's YouTube looks like in `dumpsys`: reinstalled mid-testing
            // by com.google.android.partnersetup, first install and last update
            // the same minute.
            //
            // Evaluating an update costs a policy read and one intent query, and
            // is idempotent for the overwhelmingly common case of an app policy
            // has no opinion about.
            val packageName = intent.data?.schemeSpecificPart ?: return
            // A replace is an update of something already here; a fresh arrival
            // is not. The two want different answers about spending data — see
            // below.
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            scope.launch {
                // A document first, for the reason [start] spells out: an install
                // that arrives before the policy has been read would be judged
                // against an empty one, and nothing would look at it again.
                awaitPolicy()

                // Ask the store first, because [AppBlocker.evaluate] reads a
                // cache and never waits on a network — it also runs from the
                // sweep, over every package on the device. This is the one place
                // a single install can afford a request, and without it the rule
                // would have nothing to read and would silently keep everything.
                //
                // Best-effort by construction: a failure is cached as
                // "unverified", which means keep, and Diagnostics counts it.
                //
                // **An update only asks on Wi-Fi, and a new arrival always
                // asks.** `StoreCatalogue` invalidates an entry when the
                // installed `versionCode` changes, which is the right rule — a
                // re-rating rides in on an update — but it also means Play doing
                // its ordinary job turns into a 1.2 MB listing fetch per updated
                // app, 24–48 MB a month on a normal phone, over whatever network
                // happens to be there. That is the largest running cost the store
                // rule has, and it was invisible: the periodic scan is the part
                // that looks expensive and is the part already deferred to Wi-Fi.
                //
                // The split is what the two cases are actually worth. A new
                // package has *no* verdict, and the fetch is what decides whether
                // it stays, so it is worth a metered request. An update has a
                // verdict already, and what a re-check might find is the rare
                // case of a publisher raising the rating — which can wait for
                // Wi-Fi and be picked up by the fortnightly pass, since until
                // then the app keeps the answer it had rather than none.
                if (!replacing || !StoreScan.onMeteredNetwork(appContext)) {
                    runCatching { blocker.ensureStoreAnswer(packageName) }
                        .onFailure { Log.w(TAG, "Could not ask the store about $packageName", it) }
                }

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
            // **Wait for a policy rather than sweep without one.** This service
            // starts from `Application.onCreate`, which kicks the load off in
            // parallel, so the first full sweep can easily win the race and judge
            // every package on the phone against `Policy(version = 0)`.
            //
            // Skipping is not enough, because of what comes after: the periodic
            // pass below looks only at packages that have *changed*, and an app
            // that was passed over has not changed. Anything the initial sweep
            // declined for want of a document would therefore sit there until the
            // process next started. That was survivable while removal waited for
            // the lock; it is not, now that the first sweep runs minutes after
            // installation and is the thing that makes an unlocked drawbridge
            // worth having.
            //
            // Cheap and idempotent — it reads what is on disk, or the copy
            // bundled in the APK — so this is a few milliseconds once, and free
            // afterwards. `AppBlocker.browserRuleApplies` stays as the last
            // resort for the case where even that yields nothing readable.
            awaitPolicy()

            // An initial full sweep on start covers anything installed while the
            // service was down, including across a reboot.
            runCatching { blocker.sweep() }
                .onFailure { Log.e(TAG, "Initial package sweep failed", it) }

            // **And a policy that lands later gets a sweep of its own.** See
            // [sweepOnNewPolicy]; the baseline is read here, after the sweep
            // above, so the value a StateFlow replays does not sweep twice.
            scope.launch { sweepOnNewPolicy(installedPolicyVersion()) }

            // **And the store gets asked about what was already here, now.**
            //
            // The sweep above can only read the cache — it runs over every
            // package on the phone and must never wait on a network — so on a
            // phone drawbridge has just been installed on, the cache is empty,
            // every answer is `unverified`, and `unverified` means keep. The rule
            // that catches a preloaded game therefore knows nothing until
            // something fetches the answers.
            //
            // Until 2026-08-17 the only thing that did was the lock, plus a
            // weekly rescan. That was coherent while removal itself waited for
            // the lock. It stopped being coherent the moment removal started at
            // installation: it left the phone taking apps away by name and by
            // browser rule while the store rule — the one that exists precisely
            // because a signed list cannot keep up — sat idle until somebody
            // pressed a button, or for a week if they never did.
            //
            // **Run here rather than queue a job, which is the fix for a scan
            // that never ran at all.** Measured on the owner's Moto on
            // 2026-08-18, on a phone freshly installed with build 36: `store to
            // scan: 59`, `store last fetch: (never)`, unmetered Wi-Fi, job
            // constraints satisfied, and nothing running. WorkManager cancels
            // running work when the app is replaced — which is what installing
            // drawbridge is — so the pass that matters most was the one least
            // likely to survive. See [StoreScan].
            //
            // This coroutine is the service's, so the scan lives as long as the
            // filter does and dies with it; the cache is the bookmark, so the
            // next start continues rather than restarts.
            // **On any network, as of 2026-08-18, and that is a deliberate
            // reversal.** This used to defer to Wi-Fi and queue the work
            // instead, which reads as prudent and was a hole: a phone that only
            // ever sees mobile data never scanned, so its preloaded games
            // survived indefinitely — and once anybody notices, staying off
            // Wi-Fi is a bypass rather than a delay.
            //
            // The trade is one-off. This is 50-100 MB while a phone is being set
            // up, spent once, against the rule that decides whether the phone has
            // games and companion apps on it at all. The *recurring* cost is the
            // per-update re-ask in the receiver above, and that one still waits
            // for Wi-Fi, because it repeats forever and finds something worth
            // knowing only rarely.
            StoreScan.runToCompletion(appContext)

            while (isActive) {
                delay(SWEEP_INTERVAL_MILLIS)
                sweepChanged()
            }
        }
    }

    private suspend fun awaitPolicy() {
        runCatching { DrawbridgeApplication.policy(appContext).ensureLoaded() }
            .onFailure { Log.e(TAG, "Could not load the policy before sweeping", it) }
    }

    /**
     * Sweeps every package again whenever a *newer* policy is installed.
     *
     * **The fix for a gap the Moto found on 2026-08-26.** A phone runs on the
     * copy of the policy bundled in the APK until its first poll succeeds, and
     * that copy is always behind — it is yesterday's signed document by design,
     * see the release procedure in docs/policy.md. `com.dti.motorola` was named
     * by policy 92 and 93, was not in the bundled 88, and was still sitting on
     * the phone after drawbridge had fetched and installed a document that named
     * it.
     *
     * Nothing was re-reading the installed set. `PolicyManager.applyPolicy`
     * rebuilds the DNS filter and stops there, and [sweepChanged] asks the
     * platform which packages *changed* — which a package does not do when the
     * policy does. So a newly named app waited for the next service start, for
     * the lock, or for a sweep started from the settings screen. On the phone it
     * looked like the blocklist simply not working.
     *
     * The store rule cannot cover for it either, and that is what made this
     * visible rather than academic. `com.dti.motorola` is a preinstalled system
     * app with no launcher entry, so [AppBlocker.withinStoreReach] never asks
     * about it, and Play has no listing for an OEM preload anyway, which is
     * `UNVERIFIED`, which means keep. The blocklist is the only rail these
     * packages have.
     *
     * **Forward only, and only on a version change.** `PolicyManager.clear`
     * publishes `Policy(version = 0)` as part of the sanctioned removal flow,
     * and sweeping every package against an empty document is the one thing
     * this must never do. Profile and option changes re-emit at the same
     * version and are already swept by the screen that makes them.
     *
     * @param sweptAt the version the initial sweep in [start] has covered.
     */
    private suspend fun sweepOnNewPolicy(sweptAt: Int) {
        var swept = sweptAt
        DrawbridgeApplication.policy(appContext).policy.collect { policy ->
            if (!sweepsForPolicy(swept = swept, published = policy.version)) return@collect
            swept = policy.version
            Log.i(TAG, "Policy $swept installed; re-evaluating every package against it")
            runCatching { blocker.sweep() }
                .onFailure { Log.e(TAG, "Sweep after policy $swept failed", it) }
        }
    }

    private fun installedPolicyVersion(): Int =
        DrawbridgeApplication.policy(appContext).policy.value.version

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

    internal companion object {
        private const val TAG = "PackageWatcher"
        private const val SWEEP_INTERVAL_MILLIS = 15 * 60 * 1000L

        /**
         * Whether a policy that has just been published earns a full sweep,
         * given the version the last sweep already covered.
         *
         * Expressed against plain integers rather than against the policy
         * singleton, so every branch is reachable from a unit test — the same
         * reasoning as [AppBlocker.withinStoreReach]. There are only three
         * cases and two of them must not sweep, which is why this is a named
         * function rather than a comparison inline in [sweepOnNewPolicy].
         */
        internal fun sweepsForPolicy(swept: Int, published: Int): Boolean = published > swept
    }
}
