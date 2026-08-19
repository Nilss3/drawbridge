package app.drawbridge.herald.browser

import app.drawbridge.herald.browser.ReaderViewIntegration.LoadSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a page can be asked whether it is an article, which has been got wrong
 * in a shipped build.
 *
 * It is not a rule about reader view as such. It is about *when* the browser's
 * state can be believed: a URL changes before the page it names exists, so the
 * readability question has to wait for the load it belongs to and stop being
 * asked the moment that page starts being left.
 *
 * The mono edition's other timing rule — the second step of a back press out of
 * reader view — was pinned here too, and went with always-on reader view on
 * 2026-08-19. See `docs/reader-view-back.md`.
 */
class ReaderViewIntegrationTest {

    private val article = "https://example.org/article"

    @Test
    fun `a load ending is worth asking about again`() {
        val loading = LoadSnapshot("tab", article, loading = true, active = false)
        val done = LoadSnapshot("tab", article, loading = false, active = false)

        assertTrue(done.endsALoad(loading))
    }

    @Test
    fun `a page that was already settled is not asked about again`() {
        val done = LoadSnapshot("tab", article, loading = false, active = false)

        assertFalse(done.endsALoad(done))
        assertFalse(done.endsALoad(previous = null))
    }

    /** While reader view is showing, the answer is a fixed yes from the reader. */
    @Test
    fun `a load ending in reader view is not asked about`() {
        val loading = LoadSnapshot("tab", article, loading = true, active = true)
        val done = LoadSnapshot("tab", article, loading = false, active = true)

        assertFalse(done.endsALoad(loading))
    }

    /** Reader view's own page is not a page to offer reader view for. */
    @Test
    fun `the reader's own page is not asked about`() {
        val reader = "moz-extension://abc/readerview.html?url=$article"
        val loading = LoadSnapshot("tab", reader, loading = true, active = false)
        val done = LoadSnapshot("tab", reader, loading = false, active = false)

        assertFalse(done.endsALoad(loading))
    }

    /**
     * A page that has started loading again is a page being left, and an
     * answer about it would enter reader view on the article the reader has
     * just navigated away from — which pulls them back to it, because entering
     * reader view is itself a navigation.
     */
    @Test
    fun `a page that has started loading again is not asked about`() {
        val done = LoadSnapshot("tab", article, loading = false, active = false)
        val leaving = LoadSnapshot("tab", article, loading = true, active = false)

        assertFalse(leaving.endsALoad(done))
    }

    @Test
    fun `a load ending in another tab is not asked about`() {
        val loading = LoadSnapshot("other", article, loading = true, active = false)
        val done = LoadSnapshot("tab", article, loading = false, active = false)

        assertFalse(done.endsALoad(loading))
    }
}
