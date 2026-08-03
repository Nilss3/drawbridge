package app.drawbridge.policy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A nightly window during which the device has no internet at all.
 *
 * **Not wired up yet.** The model, the window arithmetic and the Device Owner
 * calls exist and are tested; nothing reads a curfew from a live policy, and
 * `dist/policy.json` does not carry one. See
 * [docs/design-decisions.md](../../../../../../../docs/design-decisions.md).
 *
 * Enforcement is not DNS. It is the always-on VPN's *lockdown* flag, which drops
 * every packet that does not go through the tunnel — and since this filter
 * routes only DNS into its tunnel, that is every packet. Calls and SMS are
 * carrier-side and keep working, which is the property that makes this safe to
 * put on a child's phone overnight.
 *
 * Times are local wall-clock, `HH:mm`, on the 24-hour clock. That is only
 * meaningful if the clock cannot be edited, which is why a configured curfew
 * also pins the date and time settings — see
 * `DeviceOwnerManager.applyClockLock`.
 */
@Serializable
data class Curfew(
    /** Inclusive start, `HH:mm`. */
    val start: String,

    /** Exclusive end, `HH:mm`. May be earlier than [start], meaning overnight. */
    val end: String,

    /**
     * Days the window *starts* on, as English abbreviations (`mon`, `tue`, …) or
     * full names, case-insensitive. Empty means every day.
     *
     * It is the start day that is named, not every day the window touches. A
     * curfew of 21:00–07:00 on `mon` runs from Monday evening into Tuesday
     * morning; naming `tue` as well would add a second, separate window.
     */
    val days: List<String> = emptyList(),

    /**
     * Packages that keep working during the window, via the always-on VPN's
     * lockdown allowlist. Requires API 29; below that the allowlist is ignored
     * and the curfew is absolute.
     *
     * The obvious member is a messaging app a parent wants reachable at night.
     */
    @SerialName("allowed_packages")
    val allowedPackages: List<String> = emptyList(),

    /** Lets a policy carry a curfew without it being in force. */
    val enabled: Boolean = true,
) {

    private val startTime: LocalTime? get() = parseTime(start)
    private val endTime: LocalTime? get() = parseTime(end)

    /**
     * Whether the curfew covers [now].
     *
     * A window whose [end] is not after its [start] crosses midnight, and the
     * morning half belongs to the *previous* day's window — so "21:00 to 07:00
     * on Friday" is still in force at 06:00 on Saturday, and a Saturday that is
     * not itself named does not start a new one.
     */
    fun isActiveAt(now: LocalDateTime): Boolean {
        if (!enabled) return false
        val from = startTime ?: return false
        val until = endTime ?: return false
        // A zero-length window is off rather than permanently on. Expressing
        // "always" is what `enabled` and an empty policy are for.
        if (from == until) return false

        val time = now.toLocalTime()
        return if (from < until) {
            appliesOn(now.dayOfWeek) && time >= from && time < until
        } else {
            (appliesOn(now.dayOfWeek) && time >= from) ||
                (appliesOn(now.dayOfWeek.minus(1)) && time < until)
        }
    }

    /**
     * The next instant at which [isActiveAt] changes answer, or null if it never
     * does within a week.
     *
     * Deliberately found by stepping a minute at a time rather than by computing
     * boundaries directly. The direct version has to reason about midnight
     * crossings, unnamed days either side of a window, and the week wrapping —
     * three chances to be subtly wrong in a way that only shows up on one day of
     * the week. A week of minutes is ~10k evaluations of a pure comparison,
     * which costs nothing at the once-per-boundary rate this runs at.
     */
    fun nextChangeAfter(now: LocalDateTime): LocalDateTime? {
        val state = isActiveAt(now)
        var cursor = now.withSecond(0).withNano(0)
        repeat(MINUTES_IN_A_WEEK) {
            cursor = cursor.plusMinutes(1)
            if (isActiveAt(cursor) != state) return cursor
        }
        return null
    }

    private fun appliesOn(day: DayOfWeek): Boolean {
        if (days.isEmpty()) return true
        return days.any { it.trim().lowercase() in NAMES_OF[day].orEmpty() }
    }

    private companion object {
        const val MINUTES_IN_A_WEEK = 7 * 24 * 60

        val NAMES_OF: Map<DayOfWeek, Set<String>> = DayOfWeek.entries.associateWith { day ->
            val full = day.name.lowercase()
            setOf(full, full.take(3))
        }

        /** Lenient on purpose: a malformed time disables the window, never crashes. */
        fun parseTime(value: String): LocalTime? {
            val parts = value.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
}
