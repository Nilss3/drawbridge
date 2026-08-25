package app.drawbridge.dpc.security

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import app.drawbridge.dpc.R

/**
 * A lock that lets go by itself: how long this phone stays locked, and when the
 * current lock ends.
 *
 * **The key is not replaced by this and not weakened by it.** A timed lock mints,
 * shows and stores a key exactly as an untimed one does, and typing it in still
 * opens the phone at any moment. What the timer adds is a second door that opens
 * on its own, which is the difference between *"offline until I say so"* and
 * *"offline for the exam week"* — and the second is what most people actually
 * want. It is also the answer to the one failure the project could not otherwise
 * answer: a key that was never written down.
 *
 * ### Two states, and they are not the same thing
 *
 * [isEnabled] and [length] are a **draft**, like every control on the
 * configuration screen: they say what the *next* lock will do and enforce
 * nothing. [expiresAt] is the **armed** deadline of the lock that is running
 * now, written once at the moment the key is committed. Keeping them apart is
 * what lets a parent change their mind about the next lock without moving the
 * deadline of the current one — the same separation [ParentKey.commit] makes
 * between a key on screen and a key in force.
 *
 * ### Absolute wall-clock time, and why the clock gets pinned
 *
 * The deadline is a wall-clock instant, so the attack is the clock: wind the
 * phone forward forty days and the lock lifts this afternoon.
 * `SystemClock.elapsedRealtime` cannot carry the count instead, because it resets
 * at every boot and these periods are measured in days. So the defence is the
 * one the curfew already built — `DISALLOW_CONFIG_DATE_TIME` plus network time,
 * applied for as long as a timer is armed, in
 * [app.drawbridge.dpc.curfew.CurfewController].
 *
 * And where that is not enough, **ambiguity fails locked**: see [hasExpired],
 * which refuses to fire on state it cannot make sense of rather than guessing in
 * the direction that opens the phone.
 *
 * ### Deliberately trivial state
 *
 * Four values in one preference file, no derived state, no flag that can disagree
 * with the deadline. That is a requirement rather than a style: this timer is the
 * escape hatch for a lost key, it runs *inside* drawbridge, and a corrupted state
 * file would take the escape with it. Everything here has to be readable and
 * checkable by the next person holding a phone, which is also why every field is
 * printed in Diagnostics.
 */
class LockTimer(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * How long a timed lock lasts, from two hours to six months.
     *
     * The steps are coarse on purpose. This is not a stopwatch — it is *"a day
     * offline to study", "a weekend", "a week of camping", "two weeks without an
     * argument about the curfew", "a fasting period", "a school term"* — and a
     * picker of recognisable entries is answered in one glance, where a
     * free-form number of hours would have to be worked out.
     *
     * **Forty days used to be the ceiling** — Lent and Ramadan — on the argument
     * that a period nobody can shorten should not be open-ended. Two, three and
     * six months were asked for on 2026-08-24 and the argument does not survive
     * them: a term or half a year is exactly the commitment somebody choosing
     * this is trying to make, and refusing to offer it does not stop them, it
     * just sends them to *no timer at all*, which is the genuinely open-ended
     * option sitting right beside these.
     *
     * The months are thirty days each. Nobody picking "3 months" is counting to
     * the calendar date, and a length that depends on which months it happens to
     * cross would be harder to reason about than one that does not.
     *
     * **This does put the thirty-day steps in odd company.** They were the long
     * end of the list and are now the middle of it, and a parent scanning for
     * "the long one" will read past them. That is the cost, taken knowingly: the
     * alternative is a list that stops short of what people want to choose.
     *
     * The names are the stored ids, so **renaming an entry orphans a running
     * timer's length**. Nothing enforces the deadline off this value — that is
     * [expiresAt]'s job, and an unreadable length falls back to [DEFAULT] for
     * display only — but the screens would then describe a lock that is not the
     * one running.
     */
    enum class Length(
        val millis: Long,
        @param:StringRes val label: Int,
    ) {
        HOURS_2(2 * HOUR, R.string.lock_timer_hours_2),
        HOURS_4(4 * HOUR, R.string.lock_timer_hours_4),
        HOURS_8(8 * HOUR, R.string.lock_timer_hours_8),
        HOURS_12(12 * HOUR, R.string.lock_timer_hours_12),
        DAY_1(DAY, R.string.lock_timer_day_1),
        DAYS_2(2 * DAY, R.string.lock_timer_days_2),
        DAYS_3(3 * DAY, R.string.lock_timer_days_3),
        WEEK_1(7 * DAY, R.string.lock_timer_week_1),
        WEEKS_2(14 * DAY, R.string.lock_timer_weeks_2),
        WEEKS_3(21 * DAY, R.string.lock_timer_weeks_3),
        DAYS_30(30 * DAY, R.string.lock_timer_days_30),
        DAYS_40(40 * DAY, R.string.lock_timer_days_40),
        MONTHS_2(60 * DAY, R.string.lock_timer_months_2),
        MONTHS_3(90 * DAY, R.string.lock_timer_months_3),
        MONTHS_6(180 * DAY, R.string.lock_timer_months_6),
        ;

        companion object {
            fun from(name: String?): Length =
                entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /**
     * How long is left, in the coarsest unit that still says something.
     *
     * The keyguard says *"drawbridge unlocks in 3 days"* rather than naming a
     * date, and this is why it can: a duration is read at a glance by whoever
     * picks the phone up, where a date has to be compared against today. The
     * price is that the sentence goes stale, which is why
     * [LockTimerController.apply] rewrites it every time it runs.
     */
    data class Remaining(val unit: Unit, val count: Int) {
        enum class Unit { MINUTES, HOURS, DAYS }
    }

    /** Why a timer is running, because a parent reading the phone needs to know. */
    enum class Reason {
        /** Chosen on the configuration screen, before the lock. */
        CHOSEN,

        /**
         * Started from a locked phone by whoever was holding it, because the key
         * is gone. The screens say so in those words: this is the one timer a
         * parent may not have set themselves.
         */
        FORGOTTEN,
        ;

        companion object {
            fun from(name: String?): Reason? = entries.firstOrNull { it.name == name }
        }
    }

    /**
     * Whether the next lock ends by itself. **Off by default**, so a phone locked
     * by someone who never saw this control stays locked until the key opens it —
     * the behaviour every build before this one had.
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The length the next lock will use. Draft, like [isEnabled]. */
    var length: Length
        get() = Length.from(prefs.getString(KEY_LENGTH, null))
        set(value) = prefs.edit().putString(KEY_LENGTH, value.name).apply()

    /** When the running timer was armed, or 0. */
    val armedAt: Long
        get() = prefs.getLong(KEY_ARMED_AT, 0)

    /** When the running timer lifts the lock, or 0 if no timer is running. */
    val expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0)

    /** The length the running timer was armed with, for the screens to name. */
    val armedLength: Length
        get() = Length.from(prefs.getString(KEY_ARMED_LENGTH, null))

    /** Why the running timer exists, or null when none is running. */
    val reason: Reason?
        get() = if (isArmed) Reason.from(prefs.getString(KEY_REASON, null)) else null

    val isArmed: Boolean
        get() = expiresAt > 0

    /** True when the deadline has passed and the lock should come off now. */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        hasExpired(armedAt = armedAt, expiresAt = expiresAt, now = now)

    /** What is left of the running timer, for the keyguard to say. */
    fun remaining(now: Long = System.currentTimeMillis()): Remaining =
        remainingOf(expiresAt = expiresAt, now = now)

    /**
     * Starts the countdown on the lock that is running now.
     *
     * Called at two moments and only two: [app.drawbridge.dpc.ui.LockActivity]
     * sealing a lock whose draft asked for a timer, and the code-forgotten door
     * on the same screen. Both write the deadline *after* the key is in force, so
     * an abandoned reveal cannot leave a countdown running on an unlocked phone.
     */
    fun arm(length: Length, reason: Reason, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_ARMED_AT, now)
            .putLong(KEY_EXPIRES_AT, now + length.millis)
            .putString(KEY_ARMED_LENGTH, length.name)
            .putString(KEY_REASON, reason.name)
            .apply()
    }

    /**
     * Drops the running countdown, leaving the draft alone.
     *
     * Every way out of a lock comes through here: the timer firing, the key being
     * typed in, and removal. The draft survives on purpose — a parent who locked
     * for a weekend and unlocked on Sunday still wants a weekend next time.
     */
    fun disarm() {
        prefs.edit()
            .remove(KEY_ARMED_AT)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_ARMED_LENGTH)
            .remove(KEY_REASON)
            .apply()
    }

    /** Part of the sanctioned removal flow. A deadline means nothing with no lock. */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "drawbridge_lock_timer"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LENGTH = "length"
        private const val KEY_ARMED_AT = "armed_at"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ARMED_LENGTH = "armed_length"
        private const val KEY_REASON = "reason"

        private const val MINUTE = 60L * 1000L
        private const val HOUR = 60L * MINUTE
        private const val DAY = 24L * HOUR

        /**
         * What the picker starts on once the switch is turned on. A day is the
         * shortest period anybody described a use for in whole words, and the
         * hours below it are for the afternoon of an exam rather than the default
         * case.
         */
        val DEFAULT = Length.DAY_1

        /**
         * The one length the code-forgotten door offers.
         *
         * Thirty days rather than the forty the picker's longest entry allows,
         * and the difference is the point: this door is opened by whoever is
         * holding a locked phone, which may be the child. It has to be long
         * enough that starting it is not a way around the lock — a month of a
         * teenager's life is not a bypass — and short enough that a parent who
         * genuinely lost the paper is not told to come back in six weeks.
         *
         * It is deliberately not configurable. A dial on this would be a dial on
         * how long a bypass takes.
         */
        val FORGOTTEN = Length.DAYS_30

        /**
         * Whether a timer with this state has run out, as a pure function of its
         * three numbers.
         *
         * Pure and tested, like [app.drawbridge.dpc.apps.InstallLockSettings.outsideTheSet]
         * and the curfew's window arithmetic, because it decides whether a phone
         * unlocks itself and that is not a rule anybody should have to check by
         * holding a handset for two days.
         *
         * **Every uncertain case fails locked**, which is the whole of the safety
         * argument. A half-written state, a deadline that is not after the moment
         * it was armed, or a clock that now reads earlier than the arming — none
         * of those fire. The cost of failing locked is a phone that needs its key
         * or a fresh timer; the cost of failing open is a lock that lifts because
         * something was inconsistent, which is not a lock.
         *
         * Note what a *backwards* clock does here: nothing. The deadline is not
         * moved, so winding the clock back only postpones the unlock until the
         * wall clock passes it again, and winding it forward is what the clock
         * pin exists to prevent.
         */
        fun hasExpired(armedAt: Long, expiresAt: Long, now: Long): Boolean {
            if (expiresAt <= 0L || armedAt <= 0L) return false
            if (expiresAt <= armedAt) return false
            if (now < armedAt) return false
            return now >= expiresAt
        }

        /**
         * What is left, rounded to the unit somebody would say it in.
         *
         * Rounded rather than truncated, so a forty-day lock does not announce
         * *thirty-nine days* an hour after it was set — which is what floor
         * division does, and which reads as the phone having already lost a day.
         *
         * The unit is chosen by how much is actually left, and *then* the
         * rounding is checked for having pushed it over the next boundary —
         * 23h40m is *1 day* rather than *24 hours*, and 59m40s is *1 hour* rather
         * than *60 minutes*. Choosing the unit from the rounded number instead
         * reads half an hour as *1 hour*, which doubles the wait somebody is
         * standing there watching.
         *
         * Never zero. A deadline that has passed but has not fired yet — the
         * minutes between the alarm being due and something running — says *1
         * minute* rather than *0 minutes*, because the lock is genuinely still on
         * and a phone claiming to unlock in no time at all invites somebody to
         * keep pressing.
         */
        fun remainingOf(expiresAt: Long, now: Long): Remaining {
            val left = (expiresAt - now).coerceAtLeast(0)

            if (left >= DAY) {
                return Remaining(Remaining.Unit.DAYS, divideRounding(left, DAY))
            }
            if (left >= HOUR) {
                val hours = divideRounding(left, HOUR)
                return if (hours >= 24) {
                    Remaining(Remaining.Unit.DAYS, 1)
                } else {
                    Remaining(Remaining.Unit.HOURS, hours)
                }
            }
            val minutes = divideRounding(left, MINUTE)
            return if (minutes >= 60) {
                Remaining(Remaining.Unit.HOURS, 1)
            } else {
                Remaining(Remaining.Unit.MINUTES, minutes.coerceAtLeast(1))
            }
        }

        private fun divideRounding(value: Long, unit: Long): Int =
            ((value + unit / 2) / unit).toInt()
    }
}
