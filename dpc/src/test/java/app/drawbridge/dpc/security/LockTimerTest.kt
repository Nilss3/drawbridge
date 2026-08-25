package app.drawbridge.dpc.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The timer that ends a lock without a key.
 *
 * Two things are covered, and the second is the reason this file exists at all.
 * The first is the ordinary state machine: a draft that enforces nothing, an
 * armed deadline that does, and an unlock that leaves the draft alone. The second
 * is [LockTimer.hasExpired]'s **fail-locked rule** — every state it cannot make
 * sense of has to keep the phone locked, because the alternative is a lock that
 * lifts whenever something is inconsistent. That is not a rule anybody should
 * have to check by holding a handset for two days.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LockTimerTest {

    private lateinit var timer: LockTimer

    private val armed = 1_000_000_000_000L
    private val second = 1000L
    private val day = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        timer = LockTimer(ApplicationProvider.getApplicationContext())
        timer.clear()
    }

    @Test
    fun `defaults to no timer, so an upgrade changes nothing about locking`() {
        assertFalse(timer.isEnabled)
        assertFalse(timer.isArmed)
        assertNull(timer.reason)
        assertEquals(LockTimer.DEFAULT, timer.length)
        assertFalse(timer.isDue(armed + 100 * day))
    }

    @Test
    fun `the draft arms nothing on its own`() {
        timer.isEnabled = true
        timer.length = LockTimer.Length.WEEK_1

        // Choosing a period is not locking with one: the deadline is written when
        // the key is committed, so a phone that was never sealed has none.
        assertFalse(timer.isArmed)
        assertFalse(timer.isDue(armed + 100 * day))
    }

    @Test
    fun `arming records the deadline, the length and why`() {
        timer.arm(LockTimer.Length.DAYS_2, LockTimer.Reason.CHOSEN, now = armed)

        assertTrue(timer.isArmed)
        assertEquals(armed, timer.armedAt)
        assertEquals(armed + 2 * day, timer.expiresAt)
        assertEquals(LockTimer.Length.DAYS_2, timer.armedLength)
        assertEquals(LockTimer.Reason.CHOSEN, timer.reason)

        assertFalse(timer.isDue(armed))
        assertFalse(timer.isDue(armed + 2 * day - 1))
        assertTrue(timer.isDue(armed + 2 * day))
        assertTrue(timer.isDue(armed + 3 * day))
    }

    @Test
    fun `disarming keeps the draft, because the next lock probably wants the same`() {
        timer.isEnabled = true
        timer.length = LockTimer.Length.DAYS_2
        timer.arm(LockTimer.Length.DAYS_2, LockTimer.Reason.CHOSEN, now = armed)

        timer.disarm()

        assertFalse(timer.isArmed)
        assertNull(timer.reason)
        assertEquals(0, timer.expiresAt)
        // A parent who locked for a weekend and unlocked on Sunday still wants a
        // weekend next time.
        assertTrue(timer.isEnabled)
        assertEquals(LockTimer.Length.DAYS_2, timer.length)
    }

    @Test
    fun `the code-forgotten door is thirty days, not the forty the picker offers`() {
        assertEquals(LockTimer.Length.DAYS_30, LockTimer.FORGOTTEN)

        timer.arm(LockTimer.FORGOTTEN, LockTimer.Reason.FORGOTTEN, now = armed)

        assertEquals(LockTimer.Reason.FORGOTTEN, timer.reason)
        assertEquals(armed + 30 * day, timer.expiresAt)
        assertFalse(timer.isDue(armed + 29 * day))
        assertTrue(timer.isDue(armed + 30 * day))
    }

    @Test
    fun `a clock wound backwards does not fire, and does not move the deadline`() {
        timer.arm(LockTimer.Length.DAY_1, LockTimer.Reason.CHOSEN, now = armed)

        // Before the phone was even locked, which is only possible if somebody
        // moved the clock. Failing locked is the whole rule.
        assertFalse(timer.isDue(armed - 10 * day))
        // And the deadline is untouched: winding back postpones, it does not
        // shorten.
        assertEquals(armed + day, timer.expiresAt)
        assertTrue(timer.isDue(armed + day))
    }

    @Test
    fun `half-written and nonsensical state fails locked`() {
        // A deadline with no arming time, and an arming time with no deadline:
        // either is a write that did not complete, and neither may unlock a phone.
        assertFalse(LockTimer.hasExpired(armedAt = 0, expiresAt = armed, now = armed + day))
        assertFalse(LockTimer.hasExpired(armedAt = armed, expiresAt = 0, now = armed + day))
        // A deadline at or before the moment it was armed cannot be a period
        // anybody chose, so it is not treated as one that has run out.
        assertFalse(LockTimer.hasExpired(armedAt = armed, expiresAt = armed, now = armed + day))
        assertFalse(
            LockTimer.hasExpired(armedAt = armed, expiresAt = armed - day, now = armed + day),
        )
    }

    @Test
    fun `every length is a distinct period, in order, from two hours to six months`() {
        val lengths = LockTimer.Length.entries

        assertEquals(2 * 60L * 60L * 1000L, lengths.first().millis)
        // Six months of thirty-day months. The ceiling was forty days until
        // 2026-08-24; see the note on Length for why it moved.
        assertEquals(180 * day, lengths.last().millis)
        // Ordering is what the picker shows, so a list out of order would read as
        // a mistake even though nothing enforces it elsewhere.
        assertEquals(lengths.map { it.millis }.sorted(), lengths.map { it.millis })
        assertEquals(lengths.size, lengths.map { it.millis }.distinct().size)
    }

    @Test
    fun `what is left is rounded, so a fresh forty-day lock does not say thirty-nine`() {
        val hour = 60L * 60L * 1000L
        val minute = 60L * 1000L
        fun left(millis: Long) = LockTimer.remainingOf(expiresAt = armed + millis, now = armed)

        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.DAYS, 40), left(40 * day))
        // An hour into a forty-day lock. Truncating would say 39, which reads as
        // a day already gone.
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.DAYS, 40), left(40 * day - hour))
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.DAYS, 2), left(2 * day))

        // The two seams between units, which is where an off-by-one shows up as
        // "24 hours" or "60 minutes".
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.DAYS, 1), left(23 * hour + 40 * minute))
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.HOURS, 23), left(23 * hour))
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.HOURS, 1), left(59 * minute + 40 * second))
        assertEquals(LockTimer.Remaining(LockTimer.Remaining.Unit.MINUTES, 30), left(30 * minute))
    }

    @Test
    fun `a deadline that has passed still reads as a minute, never as zero`() {
        // The gap between a deadline falling due and something running. The lock
        // is genuinely still on, and "unlocks in 0 minutes" invites somebody to
        // stand there pressing buttons.
        assertEquals(
            LockTimer.Remaining(LockTimer.Remaining.Unit.MINUTES, 1),
            LockTimer.remainingOf(expiresAt = armed, now = armed),
        )
        assertEquals(
            LockTimer.Remaining(LockTimer.Remaining.Unit.MINUTES, 1),
            LockTimer.remainingOf(expiresAt = armed, now = armed + day),
        )
    }

    @Test
    fun `an unreadable length falls back rather than throwing`() {
        // The stored id is an enum name, so a build that renamed one would find a
        // value it cannot resolve. Display falls back; the deadline is unaffected,
        // because it is a number of its own rather than something derived here.
        assertEquals(LockTimer.DEFAULT, LockTimer.Length.from("WHATEVER"))
        assertEquals(LockTimer.DEFAULT, LockTimer.Length.from(null))
        assertNull(LockTimer.Reason.from("WHATEVER"))
    }
}
