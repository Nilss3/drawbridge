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
 *
 * **Nothing is exempt, including drawbridge.** An earlier version of this kept
 * the DPC's own package out of the lockdown so it could still poll, on the
 * reasoning that a phone with no internet cannot hear about the policy that
 * would give it some back. That reasoning belonged to a design where the
 * schedule came from the signed document, and it did not survive the schedule
 * becoming device-local: the way back online is a parent unlocking and changing
 * the setting, which needs no network at all. So offline means offline, which is
 * both simpler to explain and exactly what the screen promises.
 *
 * The policy does go stale while a phone is permanently offline, and that is
 * harmless: the blocklists exist to filter traffic, and there is none. A phone
 * on a curfew polls during its online hours like any other.
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

        val key = ParentKey(appContext)
        // Nothing at all before the first lock. Even switching the lockdown
        // *off* would name drawbridge as the always-on VPN and start the filter,
        // which is the whole thing the pre-lock window exists to hold back.
        if (key.protectedSince == 0L) return

        val mode = settings.mode

        // The clock lock is keyed on protection, like every other restriction: a
        // wall-clock window is only as trustworthy as the clock, and a child does
        // not stop being able to wind it forward because a parent is halfway
        // through changing a setting.
        if (mode == DisconnectSettings.Mode.CURFEW) owner.applyClockLock() else owner.clearClockLock()

        // **Offline follows the lock.** An unlocked drawbridge is a parent
        // working on the phone: installing something, moving data off it, trying
        // a browser. All of that needs a network, and an unlock that reopened
        // Settings but left the phone dark would be the same taunt as an unlock
        // that still deleted the apps you installed. It gives nothing away for
        // the same reason as everything else keyed here — unlocking costs the
        // key, and whoever holds the key can remove drawbridge outright.
        val offline = key.isLocked && settings.isOfflineAt(now)
        owner.setNetworkLockdown(enabled = offline)

        // Only a locked phone has boundaries to wake up for. Locking recomputes
        // from scratch, so nothing is lost by dropping the alarm while unlocked.
        val next = if (key.isLocked) settings.nextChangeAfter(now) else null
        if (next != null) {
            scheduleWakeUp(next)
        } else {
            cancelWakeUp()
            Log.i(TAG, "No connectivity boundary to wake for under $mode; alarm cleared")
        }
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
