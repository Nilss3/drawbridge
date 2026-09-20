package app.drawbridge.dpc.policy

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.policy.model.PolicyNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The record of what this project has said to this phone.
 *
 * One behaviour carries the feature and it is easy to get subtly wrong: a notice
 * must ring **once**. Anything else turns a channel used a handful of times in a
 * product's life into one that fires on every three-hourly poll and is muted
 * within a week — and a muted channel is worse than none, because it looks like
 * one that works.
 *
 * The rest is the archive holding what it was given, in the order it was given
 * it, and surviving a store it cannot read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NoticeInboxTest {

    private lateinit var inbox: NoticeInbox

    private val first = PolicyNotice(
        id = "2026-09-calls",
        title = "WhatsApp calls are fixed",
        body = "Update drawbridge to get the fix.",
    )

    private val second = first.copy(id = "2026-10-curfews", title = "Curfews changed")

    @Before
    fun setUp() {
        inbox = NoticeInbox(ApplicationProvider.getApplicationContext())
        inbox.clear()
    }

    // --- Filing, and ringing exactly once --------------------------------------

    @Test
    fun `a notice seen for the first time is filed and reported as new`() {
        val filed = inbox.record(first)
        assertNotNull(filed)
        assertEquals("2026-09-calls", filed!!.notice.id)
        assertEquals(1, inbox.all().size)
    }

    /**
     * The document is re-read on every poll, three-hourly. If this returned an
     * entry each time, the parent would be notified three-hourly.
     */
    @Test
    fun `the same notice seen again is not new and is not filed twice`() {
        inbox.record(first)
        assertNull(inbox.record(first))
        assertEquals(1, inbox.all().size)
    }

    @Test
    fun `a second, different notice is new and joins the first`() {
        inbox.record(first)
        assertNotNull(inbox.record(second))
        assertEquals(2, inbox.all().size)
    }

    @Test
    fun `a document with no notice files nothing`() {
        assertNull(inbox.record(null))
        assertTrue(inbox.all().isEmpty())
    }

    /** A half-written notice is a mistake, not a message. */
    @Test
    fun `a notice that is not drawable is never filed`() {
        assertNull(inbox.record(first.copy(body = "")))
        assertNull(inbox.record(first.copy(id = "")))
        assertTrue(inbox.all().isEmpty())
    }

    // --- Order -----------------------------------------------------------------

    @Test
    fun `the newest message is first`() {
        inbox.record(first)
        inbox.record(second)

        val ids = inbox.all().map { it.notice.id }
        assertEquals(listOf("2026-10-curfews", "2026-09-calls"), ids)
    }

    @Test
    fun `a filed message records when this phone saw it`() {
        val before = System.currentTimeMillis()
        val filed = requireNotNull(inbox.record(first))
        assertTrue(filed.receivedAt >= before)
        assertTrue(filed.receivedAt <= System.currentTimeMillis())
    }

    // --- Removal ---------------------------------------------------------------

    @Test
    fun `clearing empties the archive, and a cleared notice rings again`() {
        inbox.record(first)
        inbox.clear()
        assertTrue(inbox.all().isEmpty())
        assertNotNull(inbox.record(first))
    }

    /**
     * Whatever a future build changes about the stored shape, a store it cannot
     * read must not take the screen or the always-on service down with it.
     */
    @Test
    fun `an unreadable archive reads as empty rather than throwing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("drawbridge_notice", android.content.Context.MODE_PRIVATE)
            .edit().putString("received", "not json at all").commit()

        assertTrue(inbox.all().isEmpty())
        assertNotNull(inbox.record(first))
    }

    /**
     * An archive written by build 51's debug APK carries a `dismissed` flag this
     * build no longer has. A phone updating past it must read its own history
     * rather than throw it away, which is what `ignoreUnknownKeys` is for.
     */
    @Test
    fun `an archive from the build that had dismissal still reads`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("drawbridge_notice", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(
                "received",
                """[{"notice":{"id":"2026-09-calls","title":"t","body":"b"},""" +
                    """"received_at":1000,"dismissed":true}]""",
            )
            .commit()

        val held = inbox.all()
        assertEquals(1, held.size)
        assertEquals("2026-09-calls", held.single().notice.id)
    }
}
