package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CurfewTest {

    // 2026-08-03 is a Monday, which every date below is anchored to.
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 8, day, hour, minute)

    private val overnight = Curfew(start = "21:00", end = "07:00")

    @Test
    fun `an overnight window covers the evening and the following morning`() {
        assertTrue(overnight.isActiveAt(at(3, 21, 0)))
        assertTrue(overnight.isActiveAt(at(3, 23, 59)))
        assertTrue(overnight.isActiveAt(at(4, 0, 0)))
        assertTrue(overnight.isActiveAt(at(4, 6, 59)))
    }

    @Test
    fun `the window is inclusive of start and exclusive of end`() {
        assertTrue(overnight.isActiveAt(at(3, 21, 0)))
        assertFalse(overnight.isActiveAt(at(3, 20, 59)))
        assertFalse(overnight.isActiveAt(at(4, 7, 0)))
        assertTrue(overnight.isActiveAt(at(4, 6, 59)))
    }

    @Test
    fun `a same-day window does not leak into the next day`() {
        val afternoon = Curfew(start = "14:00", end = "17:00")
        assertTrue(afternoon.isActiveAt(at(3, 15)))
        assertFalse(afternoon.isActiveAt(at(3, 13)))
        assertFalse(afternoon.isActiveAt(at(3, 17)))
        assertFalse(afternoon.isActiveAt(at(4, 2)))
    }

    @Test
    fun `named days name the day the window starts on, not every day it touches`() {
        // Friday night only. Friday is the 7th; Saturday the 8th.
        val fridayNight = Curfew(start = "21:00", end = "07:00", days = listOf("fri"))

        assertTrue(fridayNight.isActiveAt(at(7, 22)))
        // Saturday morning is still Friday's window.
        assertTrue(fridayNight.isActiveAt(at(8, 6)))
        // Saturday evening starts nothing, because Saturday is not named.
        assertFalse(fridayNight.isActiveAt(at(8, 22)))
        // Nor does the morning after an unnamed evening.
        assertFalse(fridayNight.isActiveAt(at(9, 6)))
    }

    @Test
    fun `day names are accepted in full and abbreviated form, any case`() {
        val full = Curfew(start = "21:00", end = "23:00", days = listOf("MONDAY"))
        val short = Curfew(start = "21:00", end = "23:00", days = listOf("Mon"))

        assertTrue(full.isActiveAt(at(3, 22)))
        assertTrue(short.isActiveAt(at(3, 22)))
        assertFalse(full.isActiveAt(at(4, 22)))
    }

    @Test
    fun `an empty day list means every day`() {
        for (day in 3..9) {
            assertTrue("day $day", overnight.isActiveAt(at(day, 22)))
        }
    }

    @Test
    fun `disabled and malformed curfews are never active`() {
        assertFalse(overnight.copy(enabled = false).isActiveAt(at(3, 22)))
        assertFalse(Curfew(start = "not a time", end = "07:00").isActiveAt(at(3, 22)))
        assertFalse(Curfew(start = "21:00", end = "25:00").isActiveAt(at(3, 22)))
        assertFalse(Curfew(start = "21:61", end = "07:00").isActiveAt(at(3, 22)))
    }

    @Test
    fun `a zero-length window is off rather than permanently on`() {
        val zero = Curfew(start = "21:00", end = "21:00")
        assertFalse(zero.isActiveAt(at(3, 21)))
        assertFalse(zero.isActiveAt(at(3, 9)))
    }

    @Test
    fun `the next change is the start of the window when outside it`() {
        assertEquals(at(3, 21, 0), overnight.nextChangeAfter(at(3, 18, 30)))
    }

    @Test
    fun `the next change is the end of the window when inside it`() {
        assertEquals(at(4, 7, 0), overnight.nextChangeAfter(at(3, 22, 15)))
        // And from the morning half, still the same end.
        assertEquals(at(4, 7, 0), overnight.nextChangeAfter(at(4, 3, 0)))
    }

    @Test
    fun `the next change crosses to the following week for a single named day`() {
        val fridayNight = Curfew(start = "21:00", end = "07:00", days = listOf("fri"))
        // Saturday morning, just after the window ended: next is Friday the 14th.
        assertEquals(at(14, 21, 0), fridayNight.nextChangeAfter(at(8, 8, 0)))
    }

    @Test
    fun `a curfew that never changes state has no next change`() {
        assertNull(Curfew(start = "21:00", end = "21:00").nextChangeAfter(at(3, 12)))
        assertNull(overnight.copy(enabled = false).nextChangeAfter(at(3, 12)))
    }

    @Test
    fun `a profile curfew overrides the base policy`() {
        val base = Curfew(start = "22:00", end = "07:00")
        val stricter = Curfew(start = "20:00", end = "07:00")
        val policy = Policy(
            version = 1,
            curfew = base,
            defaultProfile = "everyday",
            profiles = listOf(
                Profile(id = "everyday", name = "Everyday"),
                Profile(id = "school", name = "School nights", curfew = stricter),
            ),
        )

        assertEquals(base, policy.withProfile("everyday").curfew)
        assertEquals(stricter, policy.withProfile("school").curfew)
    }
}
