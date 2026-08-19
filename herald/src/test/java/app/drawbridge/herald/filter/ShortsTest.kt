package app.drawbridge.herald.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Shorts rewrite, which has to be right about two things: that a Short opens
 * as an ordinary video, and that nothing else on YouTube is touched.
 */
class ShortsTest {

    @Test
    fun `a short becomes the same video in the ordinary player`() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            Shorts.redirected("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `the mobile front end is rewritten too, which is the one herald gets`() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc123",
            Shorts.redirected("https://m.youtube.com/shorts/abc123"),
        )
        assertEquals(
            "https://youtube.com/watch?v=abc123",
            Shorts.redirected("https://youtube.com/shorts/abc123"),
        )
    }

    @Test
    fun `the feed entry point goes home, because there is no video to send anyone to`() {
        assertEquals("https://www.youtube.com/", Shorts.redirected("https://www.youtube.com/shorts"))
        assertEquals("https://m.youtube.com/", Shorts.redirected("https://m.youtube.com/shorts/"))
    }

    @Test
    fun `a shared link keeps whatever it was carrying`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123&feature=share",
            Shorts.redirected("https://www.youtube.com/shorts/abc123?feature=share"),
        )
    }

    @Test
    fun `a v of its own does not arrive twice`() {
        // Otherwise the page gets two v parameters and picks whichever it likes,
        // which could be a different video than the link named.
        assertEquals(
            "https://www.youtube.com/watch?v=abc123&t=5",
            Shorts.redirected("https://www.youtube.com/shorts/abc123?v=zzz&t=5"),
        )
    }

    @Test
    fun `a fragment survives`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123#t=30",
            Shorts.redirected("https://www.youtube.com/shorts/abc123#t=30"),
        )
    }

    @Test
    fun `the rewritten url is left alone, which is what stops a loop`() {
        assertNull(Shorts.redirected("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `the rest of youtube is untouched`() {
        assertNull(Shorts.redirected("https://www.youtube.com/"))
        assertNull(Shorts.redirected("https://www.youtube.com/results?search_query=cats"))
        assertNull(Shorts.redirected("https://www.youtube.com/@somechannel"))
        assertNull(Shorts.redirected("https://music.youtube.com/watch?v=abc123"))
    }

    @Test
    fun `other sites are untouched, including ones with a shorts path`() {
        assertNull(Shorts.redirected("https://example.com/shorts/abc123"))
        assertNull(Shorts.redirected("https://notyoutube.com/shorts/abc123"))
        // The embed host has no Shorts surface; rewriting one would break a page
        // that merely quotes a video.
        assertNull(Shorts.redirected("https://www.youtube-nocookie.com/shorts/abc123"))
    }

    @Test
    fun `a deeper path is not a short and is left alone`() {
        assertNull(Shorts.redirected("https://www.youtube.com/shorts/abc123/something"))
    }

    @Test
    fun `non-http schemes are ignored`() {
        assertNull(Shorts.redirected("about:blank"))
        assertNull(Shorts.redirected("data:text/html,hello"))
        assertNull(Shorts.redirected(""))
    }
}
