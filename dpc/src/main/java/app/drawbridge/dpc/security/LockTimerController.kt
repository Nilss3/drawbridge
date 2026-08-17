package app.drawbridge.dpc.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.curfew.CurfewController

/**
 * Lifts the lock when its timer runs out, and arranges to be woken when that
 * moment comes.
 *
 * Stateless in the same way [CurfewController] is, and for the same reason: every
 * run asks [LockTimer] what the deadline is, asks the clock where we are, and
 * acts. There is no "counting down" flag to get out of step with reality after a
 * reboot, a lost alarm, or a parent unlocking with the key halfway through.
 *
 * **Nothing here is a substitute for the key.** Typing the key in still unlocks
 * the phone at any moment, and doing so cancels the countdown — see
 * [app.drawbridge.dpc.ui.LockActivity]. This is the other door, on a clock.
 *
 * ### Three ways it gets run, because failing to run is the bad failure
 *
 * A curfew that fails leaves a phone online or offline for a few hours. A timer
 * that fails leaves a phone locked forever with a key that may not exist, which
 * is precisely the outcome the timer was built to prevent. So it is driven from
 * every angle:
 *
 *  - the **alarm** below, which is the punctual one;
 *  - **every process start and every boot**, because an alarm does not survive
 *    a reboot or an app upgrade;
 *  - [LockTimerWorker], hourly, for the case where the alarm was silently lost.
 *
 * All three call [apply], all three are idempotent, and none of them trusts the
 * others to have run.
 */
class LockTimerController(context: Context) {

    private val appContext = context.applicationContext
    private val timer = LockTimer(appContext)
    private val parentKey = ParentKey(appContext)

    /**
     * Unlocks if the deadline has passed, and otherwise makes sure something will
     * wake up when it does.
     *
     * @return true if this call unlocked the phone.
     */
    fun apply(now: Long = System.currentTimeMillis()): Boolean {
        if (!timer.isArmed) {
            // No deadline, so no alarm should be pending either. This is the line
            // that cleans up after removal and after an unlock that happened
            // while the process was dead.
            cancelWakeUp()
            return false
        }

        // The lock is already gone — the key was typed in, or drawbridge was
        // removed — so the deadline is a leftover. Every one of those paths
        // disarms explicitly; this repairs the case where one did not get the
        // chance, rather than leaving a countdown pointed at nothing.
        if (!parentKey.isLocked) {
            Log.i(TAG, "Timer armed on an unlocked phone; dropping it")
            timer.disarm()
            cancelWakeUp()
            return false
        }

        if (!timer.isDue(now)) {
            // The keyguard says "drawbridge unlocks in 3 days", and that sentence
            // is a stored string rather than something the system re-resolves —
            // so it is only ever as fresh as the last time somebody wrote it.
            // This is that somebody: process start, boot, and [LockTimerWorker]
            // hourly, which is why an hourly worker for a day-scale countdown is
            // not the waste it looks like.
            DeviceOwnerManager(appContext).updateLockScreenInfo()
            scheduleWakeUp(timer.expiresAt)
            return false
        }

        unlockNow()
        return true
    }

    /**
     * The automatic unlock, which is the same unlock the key performs.
     *
     * It removes the key and nothing else: the filter, the always-on VPN and the
     * restriction set are keyed on [ParentKey.protectedSince], which survives
     * this exactly as it survives a parent unlocking to change a setting. What
     * comes back is the configuration screen, USB debugging, and the network —
     * and, through the configuration screen's overflow menu, the ability to
     * remove drawbridge altogether. That last one is the point of the forty-day
     * door and the reason the countdown is on the keyguard.
     *
     * The order is [app.drawbridge.dpc.ui.LockActivity.attemptUnlock]'s order,
     * for the reasons documented there: the key goes first, because everything
     * below reads the lock state, and the timer is disarmed before the curfew is
     * re-evaluated because that is what decides whether the clock stays pinned.
     *
     * **No policy fetch, unlike the manual unlock.** That one runs because a
     * parent is standing there about to use the network they just got back; this
     * one may fire at four in the morning, and a phone that has just come back
     * online has the ordinary three-hourly poll to catch up with.
     */
    private fun unlockNow() {
        Log.i(TAG, "Lock timer expired (${timer.reason}); unlocking")
        parentKey.forget()
        timer.disarm()
        cancelWakeUp()

        val owner = DeviceOwnerManager(appContext)
        // The keyguard stops claiming a lock date, and stops naming a deadline
        // that has been reached. It still says drawbridge is guarding the phone,
        // because it is.
        owner.updateLockScreenInfo()
        // USB debugging follows the lock rather than the protection, so this is
        // where it comes back. See DeviceOwnerManager.restrictionsFor.
        owner.applyUserRestrictions()
        // And the phone comes back online whatever the disconnect philosophy
        // says, because an unlocked drawbridge is a phone somebody is about to
        // work on. This also clears the clock pin if no curfew still needs it.
        CurfewController(appContext).apply()
    }

    /**
     * Wakes drawbridge at the deadline.
     *
     * `setAndAllowWhileIdle` rather than an exact alarm, for the same reason the
     * curfew uses it: exact alarms need a Play-policy-restricted permission from
     * API 31, and a lock that lifts at 09:04 instead of 09:00 after two weeks is
     * not worth one. Doze can defer this by minutes; [LockTimerWorker] and every
     * process start are what cover it being lost outright.
     */
    private fun scheduleWakeUp(at: Long) {
        val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            Log.i(TAG, "Lock timer lifts at $at")
        }.onFailure { Log.e(TAG, "Could not schedule the unlock", it) }
    }

    private fun cancelWakeUp() {
        val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.cancel(pendingIntent()) }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, LockTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "LockTimerController"

        /** Distinct from the curfew's, or one feature would cancel the other's alarm. */
        const val REQUEST_CODE = 0x0C10
    }
}
