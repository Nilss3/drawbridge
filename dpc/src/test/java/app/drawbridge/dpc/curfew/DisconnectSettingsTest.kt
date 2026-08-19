package app.drawbridge.dpc.curfew

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
import java.time.LocalDateTime

/**
 * The disconnect philosophy, and the two windows it turns into.
 *
 * The window arithmetic itself belongs to [app.drawbridge.policy.model.Curfew]
 * and is tested there. What is covered here is the part this class adds: that
 * the right days end up on the right window, and that a Friday night runs into
 * Saturday morning rather than stopping at midnight — the case a weekday /
 * weekend split makes easy to get wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DisconnectSettingsTest {

    private lateinit var settings: DisconnectSettings

    @Before
    fun setUp() {
        settings = DisconnectSettings(ApplicationProvider.getApplicationContext())
        settings.clear()
    }

    // 2026-08-10 is a Monday, so the days below are unambiguous.
    private fun monday(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, 10, hour, minute)
    private fun friday(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, 14, hour, minute)
    private fun saturday(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, 15, hour, minute)
    private fun sunday(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, 16, hour, minute)

    @Test
    fun `defaults to always online, so an upgrade changes nothing`() {
        assertEquals(DisconnectSettings.Mode.ONLINE, settings.mode)
        assertFalse(settings.isOfflineAt(monday(23)))
        assertTrue(settings.curfews().isEmpty())
    }

    @Test
    fun `always offline means offline, at every hour of every day`() {
        settings.mode = DisconnectSettings.Mode.OFFLINE

        assertTrue(settings.isOfflineAt(monday(3)))
        assertTrue(settings.isOfflineAt(saturday(14)))
        // Nothing to wake up for: there is no boundary to cross.
        assertNull(settings.nextChangeAfter(monday(3)))
    }

    @Test
    fun `the default curfew is nine in the evening to eight in the morning`() {
        settings.mode = DisconnectSettings.Mode.CURFEW

        assertEquals("21:00", settings.weekdayWindow.start)
        assertEquals("08:00", settings.weekdayWindow.end)
        assertEquals("21:00", settings.weekendWindow.start)
        assertEquals("08:00", settings.weekendWindow.end)

        assertTrue("22:00 on a Monday is inside the window", settings.isOfflineAt(monday(22)))
        assertTrue("07:00 on a Monday is still last night's", settings.isOfflineAt(monday(7)))
        assertFalse("midday is not", settings.isOfflineAt(monday(12)))
        assertFalse("eight is the moment it lifts", settings.isOfflineAt(monday(8)))
    }

    @Test
    fun `a Friday night window runs into Saturday morning`() {
        // The case a weekday/weekend split invites getting wrong: Saturday
        // morning belongs to the window that started on Friday, and is covered
        // by the *weekday* rule rather than the weekend one.
        settings.mode = DisconnectSettings.Mode.CURFEW

        assertTrue(settings.isOfflineAt(friday(23)))
        assertTrue(settings.isOfflineAt(saturday(2)))
        assertFalse(settings.isOfflineAt(saturday(9)))
        // And Saturday evening starts the weekend window, into Sunday.
        assertTrue(settings.isOfflineAt(saturday(22)))
        assertTrue(settings.isOfflineAt(sunday(6)))
    }

    @Test
    fun `weekend hours are independent of weekday hours`() {
        settings.mode = DisconnectSettings.Mode.CURFEW
        settings.weekdayWindow = DisconnectSettings.Window("21:00", "08:00")
        settings.weekendWindow = DisconnectSettings.Window("23:30", "10:00")

        assertTrue("a weeknight still ends at eight", settings.isOfflineAt(monday(7)))
        assertFalse("Saturday at 23:00 is still allowed", settings.isOfflineAt(saturday(23)))
        assertTrue("and offline half an hour later", settings.isOfflineAt(saturday(23, 45)))
        assertTrue("Sunday lie-in", settings.isOfflineAt(sunday(9)))
        assertFalse(settings.isOfflineAt(sunday(10, 30)))
    }

    @Test
    fun `the next change is the earlier of the two windows`() {
        settings.mode = DisconnectSettings.Mode.CURFEW

        val next = settings.nextChangeAfter(monday(12))
        assertEquals("the alarm should be the start of tonight's window", monday(21), next)
    }

    @Test
    fun `settings survive being read back`() {
        settings.mode = DisconnectSettings.Mode.CURFEW
        settings.weekdayWindow = DisconnectSettings.Window("22:15", "06:45")

        val reread = DisconnectSettings(ApplicationProvider.getApplicationContext())
        assertEquals(DisconnectSettings.Mode.CURFEW, reread.mode)
        assertEquals("22:15", reread.weekdayWindow.start)
        assertEquals("06:45", reread.weekdayWindow.end)
    }

    @Test
    fun `each window carries only its own days`() {
        settings.mode = DisconnectSettings.Mode.CURFEW
        val curfews = settings.curfews()

        assertEquals(2, curfews.size)
        assertEquals(listOf("mon", "tue", "wed", "thu", "fri"), curfews[0].days)
        assertEquals(listOf("sat", "sun"), curfews[1].days)
    }
}
