package app.drawbridge.dpc.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.policy.model.Curfew
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Applies the [Curfew] window and arranges to be woken at the next boundary.
 *
 * **Not yet reachable.** Nothing calls [apply] on a live device; the entry
 * points are drafted and the logic is tested, but no shipped policy carries a
 * curfew. Wiring it up means calling [apply] from `BootReceiver`, after a policy
 * refresh, and from [CurfewReceiver].
 *
 * The design is deliberately stateless: every run asks the policy what the
 * window is, asks the clock where we are in it, and sets the device to match.
 * There is no "curfew is running" flag to get out of step with reality after a
 * reboot, a policy change, or a missed alarm.
 */
class CurfewController(context: Context) {

    private val appContext = context.applicationContext
    private val owner = DeviceOwnerManager(appContext)

    /**
     * Brings the device in line with [curfew] and schedules the next change.
     *
     * A null curfew is not "do nothing" — it has to actively lift a lockdown a
     * previous policy imposed, or dropping the curfew from the policy would
     * leave the last device that applied it dark forever.
     */
    fun apply(curfew: Curfew?, now: LocalDateTime = LocalDateTime.now()) {
        if (!owner.isDeviceOwner) return

        if (curfew == null) {
            owner.setNetworkLockdown(enabled = false)
            owner.clearClockLock()
            cancelWakeUp()
            return
        }

        // A wall-clock window is only as trustworthy as the clock, so the lock
        // goes on whenever a curfew exists rather than only while one is running.
        owner.applyClockLock()

        val active = curfew.isActiveAt(now)
        owner.setNetworkLockdown(
            enabled = active,
            allowedPackages = curfew.allowedPackages.toSet(),
        )

        curfew.nextChangeAfter(now)?.let(::scheduleWakeUp)
            ?: Log.w(TAG, "Curfew never changes state; not scheduling a wake-up")
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
