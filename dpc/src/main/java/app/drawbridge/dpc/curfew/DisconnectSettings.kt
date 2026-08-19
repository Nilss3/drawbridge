package app.drawbridge.dpc.curfew

import android.content.Context
import android.content.SharedPreferences
import app.drawbridge.policy.model.Curfew

/**
 * How much internet this phone is allowed to have, and when.
 *
 * **Device-local, not policy.** The signed document says what the *web* may
 * contain; this says whether the phone may reach it at all, and the times differ
 * per household. A document signed by this project's key cannot carry "offline
 * at nine on weeknights" for somebody else's teenager, which is the same
 * reasoning that keeps the chosen profile and options on the device.
 *
 * `Policy.curfew` still exists and is still parsed. It is now a *default* a
 * document may suggest rather than the thing enforced; what enforcement reads is
 * this.
 */
class DisconnectSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The three philosophies, in the order the configuration screen shows them. */
    enum class Mode {
        /** No IP traffic at all, ever. Calls, SMS, GPS and FM radio are untouched. */
        OFFLINE,

        /** What every device did before this existed, and still the default. */
        ONLINE,

        /** Offline between [weekdayWindow] and [weekendWindow]'s hours. */
        CURFEW,
        ;

        companion object {
            fun from(name: String?): Mode =
                entries.firstOrNull { it.name == name } ?: ONLINE
        }
    }

    /** A wall-clock window the phone is *offline*, `HH:mm` to `HH:mm`. */
    data class Window(val start: String, val end: String)

    var mode: Mode
        get() = Mode.from(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Monday to Friday evening. The window that starts on a Friday runs into Saturday. */
    var weekdayWindow: Window
        get() = window(KEY_WEEKDAY_START, KEY_WEEKDAY_END)
        set(value) = putWindow(KEY_WEEKDAY_START, KEY_WEEKDAY_END, value)

    /** Saturday and Sunday evening. The Sunday window runs into Monday morning. */
    var weekendWindow: Window
        get() = window(KEY_WEEKEND_START, KEY_WEEKEND_END)
        set(value) = putWindow(KEY_WEEKEND_START, KEY_WEEKEND_END, value)

    /**
     * The two windows as the policy model's own [Curfew] type, or an empty list
     * when no curfew applies.
     *
     * Days are named by the day the window *starts* on, which is what makes an
     * overnight window belong to the evening that began it: Friday 21:00–08:00
     * covers Saturday morning, and Saturday's own window then starts that
     * evening. Naming Saturday in the weekday set as well would produce two
     * overlapping windows and a Saturday morning that is offline twice.
     */
    fun curfews(): List<Curfew> = when (mode) {
        Mode.CURFEW -> listOf(
            Curfew(
                start = weekdayWindow.start,
                end = weekdayWindow.end,
                days = WEEKDAYS,
                allowedPackages = listOf(),
            ),
            Curfew(
                start = weekendWindow.start,
                end = weekendWindow.end,
                days = WEEKEND,
                allowedPackages = listOf(),
            ),
        )

        else -> emptyList()
    }

    /** True while any configured window covers [now]. */
    fun isOfflineAt(now: java.time.LocalDateTime): Boolean = when (mode) {
        Mode.OFFLINE -> true
        Mode.ONLINE -> false
        Mode.CURFEW -> curfews().any { it.isActiveAt(now) }
    }

    /**
     * The next instant the answer changes, or null when it never does — which is
     * both constant modes, and is why the caller must treat null as "no alarm
     * needed" rather than as an error.
     */
    fun nextChangeAfter(now: java.time.LocalDateTime): java.time.LocalDateTime? =
        curfews().mapNotNull { it.nextChangeAfter(now) }.minOrNull()

    fun clear() = prefs.edit().clear().apply()

    private fun window(startKey: String, endKey: String) = Window(
        start = prefs.getString(startKey, null) ?: DEFAULT_START,
        end = prefs.getString(endKey, null) ?: DEFAULT_END,
    )

    private fun putWindow(startKey: String, endKey: String, value: Window) =
        prefs.edit().putString(startKey, value.start).putString(endKey, value.end).apply()

    companion object {
        private const val PREFS_NAME = "drawbridge_disconnect"
        private const val KEY_MODE = "mode"
        private const val KEY_WEEKDAY_START = "weekday_start"
        private const val KEY_WEEKDAY_END = "weekday_end"
        private const val KEY_WEEKEND_START = "weekend_start"
        private const val KEY_WEEKEND_END = "weekend_end"

        /** Nine in the evening until eight in the morning, as asked for. */
        const val DEFAULT_START = "21:00"
        const val DEFAULT_END = "08:00"

        val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri")
        val WEEKEND = listOf("sat", "sun")
    }
}
