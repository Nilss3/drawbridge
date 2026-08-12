package app.drawbridge.dpc.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.security.ParentKey
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Puts the device's connectivity where [DisconnectSettings] says it should be,
 * and arranges to be woken when that answer next changes.
 *
 * The design is deliberately stateless: every run asks the settings what the
 * window is, asks the clock where we are in it, and sets the device to match.
 * There is no "curfew is running" flag to get out of step with reality after a
 * reboot, a settings change, or a missed alarm.
 *
 * **What "offline" reaches.** Enforcement is the always-on VPN's lockdown flag,
 * which is a rule about the *user*, not about a network: every packet from every
 * app must go through the tunnel, and this tunnel carries nothing but DNS. So it
 * covers Wi-Fi, mobile data, Bluetooth tethering from a second device and USB
 * ethernet alike, without naming any of them. Calls and SMS are carrier-side and
 * unaffected, and GPS and FM radio are receive-only — which is exactly the
 * "calls and SMS only" the offline mode promises.
 */
class CurfewController(context: Context) {

    private val appContext = context.applicationContext
    private val owner = DeviceOwnerManager(appContext)
    private val settings = DisconnectSettings(appContext)

    /**
     * Brings the device in line with the chosen philosophy and schedules the
     * next change.
     *
     * "Always online" is not "do nothing" — it has to actively lift a lockdown a
     * previous setting imposed, or switching the curfew off would leave the
     * phone dark forever.
     */
    fun apply(now: LocalDateTime = LocalDateTime.now()) {
        if (!owner.isDeviceOwner) return

        val mode = settings.mode
        if (mode == DisconnectSettings.Mode.ONLINE) {
            owner.setNetworkLockdown(enabled = false)
            owner.clearClockLock()
            cancelWakeUp()
            return
        }

        // A wall-clock window is only as trustworthy as the clock, so the lock
        // goes on whenever a curfew exists rather than only while one is running.
        // Permanent offline does not depend on the clock and does not need it.
        if (mode == DisconnectSettings.Mode.CURFEW) owner.applyClockLock() else owner.clearClockLock()

        owner.setNetworkLockdown(
            enabled = settings.isOfflineAt(now),
            allowedPackages = alwaysAllowed(),
        )

        val next = settings.nextChangeAfter(now)
        if (next != null) {
            scheduleWakeUp(next)
        } else {
            // Both constant modes land here, and so does a curfew whose two
            // windows never change state -- which the model reports rather than
            // guesses at. Nothing to wake up for.
            cancelWakeUp()
            Log.i(TAG, "Connectivity never changes state under $mode; no wake-up scheduled")
        }
    }

    /**
     * drawbridge itself keeps its network, in every mode.
     *
     * **This is what stops a curfew that cannot lift**, which is the failure
     * worth designing against here: a phone with no internet has no way to hear
     * about the policy that would give it some back, and "offline until someone
     * drives to the house" is not a feature. The DPC is the one package that
     * must keep polling — it carries no browsing surface of its own, so
     * exempting it hands nobody anything.
     *
     * It also answers the "guaranteed thirty minutes a day" requirement more
     * cleanly than a hole in the schedule would: the phone can always fetch its
     * policy and its updates, and the *user* is still offline the whole time.
     *
     * **The allowlist needs API 29** and `minSdk` is 28, where
     * [DeviceOwnerManager.setNetworkLockdown] drops it and the lockdown is
     * absolute. On such a device an offline phone really cannot poll, and the
     * only way back is the configuration screen in somebody's hand.
     */
    private fun alwaysAllowed(): Set<String> = setOf(appContext.packageName)

    /**
     * The automatic entry point: boot, policy refresh, and anything else that
     * runs without a person present.
     *
     * Keyed on `protectedSince` rather than on the lock, because connectivity is
     * enforcement of the same kind as the filter — a parent who unlocks to change
     * a setting has not asked for the internet back, and a phone that came back
     * online every time somebody opened the configuration screen would be a
     * curfew in name only. The setting itself is one tap away on that screen for
     * a parent who does want it back.
     *
     * Contrast [app.drawbridge.dpc.apps.AppBlocker], which *is* keyed on the
     * lock: the difference is between taking something away and letting the
     * phone keep doing what it was already doing.
     */
    fun applyIfProtected(now: LocalDateTime = LocalDateTime.now()) {
        if (ParentKey(appContext).protectedSince == 0L) return
        apply(now)
    }

    /**
     * Wakes drawbridge at [at] to re-evaluate.
     *
     * `setAndAllowWhileIdle` rather than an exact alarm: exact alarms need
     * `SCHEDULE_EXACT_ALARM` from API 31, which is a user-visible grant and a
     * Play-policy-restricted permission — a real cost for a feature that does
     * not need second accuracy. Doze may defer this by a few minutes, and a
     * curfew that starts at 21:03 instead of 21:00 is not a defect worth a new
     * permission for.
     *
     * The alarm is a backstop in any case: [apply] recomputes from the clock
     * every time it runs, so a dropped alarm is corrected at the next boot or
     * policy refresh rather than leaving the device in the wrong state forever.
     */
    private fun scheduleWakeUp(at: LocalDateTime) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent())
            Log.i(TAG, "Next curfew change at $at")
        }.onFailure { Log.e(TAG, "Could not schedule the curfew wake-up", it) }
    }

    private fun cancelWakeUp() {
        val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.cancel(pendingIntent()) }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, CurfewReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "CurfewController"
        const val REQUEST_CODE = 0x0C0F
    }
}
