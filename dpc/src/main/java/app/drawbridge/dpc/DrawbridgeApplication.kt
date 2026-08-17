package app.drawbridge.dpc

import android.app.Application
import android.content.Context
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.apps.store.StoreScanWorker
import app.drawbridge.dpc.curfew.CurfewController
import app.drawbridge.dpc.curfew.CurfewWorker
import app.drawbridge.dpc.security.LockTimerController
import app.drawbridge.dpc.security.LockTimerWorker
import app.drawbridge.dpc.update.UpdateWorker
import app.drawbridge.dpc.vpn.DnsFilterService
import app.drawbridge.policy.PolicyConfig
import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.work.PolicyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DrawbridgeApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch { policy(this@DrawbridgeApplication).ensureLoaded() }
        PolicyWorker.schedule(this)
        UpdateWorker.schedule(this)
        // Weekly, on Wi-Fi, and mostly a job that finds nothing to do: only a
        // cache entry that has expired costs a request. What it is for is a
        // publisher re-rating an app that is already on the phone, which nothing
        // else on the device would ever reveal.
        StoreScanWorker.schedule(this)

        // Restrictions can be dropped by an OS upgrade, so re-assert them on every
        // process start rather than only at provisioning time.
        DeviceOwnerManager(this).reapplyIfProtected()

        // The filter, which does *not* wait for the lock. See startFiltering.
        startFiltering(this)

        // Connectivity is recomputed from the clock on every process start too.
        // The alarm is the primary mechanism and this is the backstop: Doze can
        // defer an inexact alarm, and a phone that missed its 08:00 boundary
        // should not stay offline until 21:00 because of it.
        //
        // Note this does *not* need to run after a policy refresh any more. The
        // schedule is device-local, so no document can change it.
        CurfewController(this).apply()
        // The alarm is punctual and this is not; between them, a boundary that
        // is missed is late rather than permanent. See CurfewWorker.
        CurfewWorker.schedule(this)

        // The lock's own clock, on the same three legs and for a stricter reason:
        // a curfew that misses a boundary is a phone online at the wrong hour,
        // and a timer that never fires is a phone nobody can open. This call is
        // what covers an app upgrade or a force-stop having taken the alarm.
        LockTimerController(this).apply()
        LockTimerWorker.schedule(this)
    }

    companion object {
        /** drawbridge and herald read the same signed document from the same URL. */
        // BuildConfig.POLICY_URL is main's URL unless a build overrides it; see
        // dpc/build.gradle.kts. The dev channel is the only thing that does.
        val policyConfig = PolicyConfig(policyUrl = BuildConfig.POLICY_URL)

        fun policy(context: Context): PolicyManager =
            PolicyManager.getInstance(context, policyConfig)

        /** Called when the admin receiver is enabled or provisioning finishes. */
        fun onAdminEnabled(context: Context) {
            DeviceOwnerManager(context).reapplyIfProtected()

            // Herald comes down now, before the phone is locked, and that is a
            // deliberate reversal.
            //
            // It used to wait for the lock, because this method ran *inside* the
            // QR setup wizard and starting a ~470 MiB download underneath the
            // wizard is what left a Moto G15 unable to finish setup at all. With
            // the QR path retired there is no such moment left: provisioning
            // happens over a cable, on a phone that has already finished setup.
            //
            // And there is a reason to want it early. The window before the lock
            // is the only time the parent has both browsers in front of them, so
            // it is the only time they can move bookmarks out of the browser they
            // are about to lose. A herald that appears after the lock is a herald
            // that appears after their bookmarks are gone.
            //
            // Nothing here enforces anything: no restriction is applied, no app
            // is removed. Installing what the policy *requires* is additive, and
            // it is the removals that wait for the parent to ask.
            fetchPolicyAndRequiredApps(context)

            // And the filter, from the moment drawbridge owns the phone.
            startFiltering(context)
        }

        /**
         * Brings up the DNS filter, without waiting for the lock.
         *
         * **Changed 2026-08-12, on the owner's decision, after a Nothing Phone was
         * provisioned and sat unfiltered until somebody pressed Lock.** Everything
         * else still waits; this does not. Three of the four reasons it used to
         * wait have expired:
         *
         *  - applying policy *inside the setup wizard* bricked a QR provision on
         *    2026-08-07, and the QR path is retired. The `isSetupComplete` guard
         *    below keeps that lesson anyway, for nothing;
         *  - applying policy at first launch took Facebook, 470 MiB and USB
         *    debugging before anyone agreed — but that was the removals and the
         *    restrictions, not the filter;
         *  - starting this service also starts [PackageWatcher], so the filter
         *    used to drag app removal along with it. Removal is keyed on the lock
         *    now, so that entanglement is gone and this takes nothing away.
         *
         * **And the rule it replaces was not consistent.** If "not locked" meant
         * "not filtered", then unlocking would have to un-filter the phone too —
         * that is the same state. It never did, and nobody noticed. A filter that
         * runs from installation onwards has no such seam: the deliberate act is
         * installing drawbridge.
         *
         * A phone that wants no web filter should say so in its *policy*, which
         * is a choice somebody makes, rather than getting one by leaving a button
         * unpressed.
         */
        fun startFiltering(context: Context) {
            val owner = DeviceOwnerManager(context)
            if (!owner.isDeviceOwner) return
            // The one part of the old deferral worth keeping: never while the
            // setup wizard is still on screen.
            if (!ProvisioningLog.isSetupComplete(context)) return

            owner.enableAlwaysOnVpn()
            // Returns a consent intent only when drawbridge is not Device Owner,
            // which the check above has already excluded.
            DnsFilterService.requestStart(context)
        }

        /**
         * Fetches the current policy and installs whatever it requires.
         *
         * Called at provisioning and again when the parent locks the phone. It
         * only ever *adds*: the restrictions and the app removals are
         * [DeviceOwnerManager]'s and [AppBlocker]'s, and those still wait for the
         * lock. The filter no longer does — see [startFiltering].
         */
        fun fetchPolicyAndRequiredApps(context: Context) {
            ProvisioningLog.record(context, "fetchPolicyAndRequiredApps: policy refresh + required apps")
            PolicyWorker.refreshNow(context)
            UpdateWorker.runNow(context)
        }

        /**
         * Process-scoped, because the work below has to outlive the activity that
         * asks for it: [LockActivity] finishes itself in the same breath.
         */
        private val lockScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Removes everything the policy disallows, the moment the phone becomes
         * locked.
         *
         * **This is what makes removal-follows-the-lock complete**, and it cannot
         * be left to [PackageWatcher]. That watcher does a full sweep when
         * [DnsFilterService] starts — but on a first lock the service is started
         * by `MainActivity.lockDevice`, which runs *before* the parent has
         * decided to keep the key, so the sweep would find an unlocked phone and
         * do nothing. Its periodic pass is fifteen minutes away, and a parent who
         * presses Lock is entitled to see the phone change now.
         *
         * Called from [LockActivity.sealWithKey], after `ParentKey.commit` and
         * for the same reason USB debugging is applied there: until the key is
         * committed there is no lock, and an abandoned reveal must leave the
         * phone as it was.
         *
         * **It closes the install lock's set first, and the order is the whole
         * feature.** That set is what a locked phone measures new apps against,
         * and the sweep below is what enforces it — so a sweep that ran against
         * the *previous* lock's snapshot would remove exactly the app the parent
         * unlocked the phone to install. Unlock, install, lock again: the middle
         * step only works because this line comes before the next one. See
         * [AppBlocker.closeTheInstalledSet].
         */
        fun sweepOnLock(context: Context) {
            val appContext = context.applicationContext

            // The catch-up pass, queued rather than run: it is 50-100 MB and
            // waits for Wi-Fi, so it cannot be part of the work a parent stands
            // and watches. Queued *here* because the lock is when the store rule
            // starts applying and therefore the first moment the phone needs
            // answers about what is already on it — the install receiver only
            // ever covers what arrives next, which is the wrong half on a phone
            // somebody installed drawbridge to fix.
            StoreScanWorker.runNow(appContext)

            lockScope.launch {
                val blocker = AppBlocker(appContext)
                runCatching { blocker.closeTheInstalledSet() }
                    .onFailure { Log.e(TAG, "Could not record the installed set", it) }
                runCatching { blocker.sweep() }
                    .onSuccess { actions ->
                        // Counted apart, because they used to be counted
                        // together and a sweep that failed on every package
                        // reported the same number as one that removed them all.
                        val failed = actions.filterValues { it == AppBlocker.Action.FAILED }.keys
                        val handled = actions.keys - failed
                        Log.i(TAG, "Lock sweep removed ${handled.size} packages: $handled")
                        if (failed.isNotEmpty()) {
                            Log.w(TAG, "Lock sweep could not remove ${failed.size}: $failed")
                        }
                    }
                    .onFailure { Log.e(TAG, "Lock sweep failed", it) }
            }
        }

        private const val TAG = "DrawbridgeApplication"
    }
}
