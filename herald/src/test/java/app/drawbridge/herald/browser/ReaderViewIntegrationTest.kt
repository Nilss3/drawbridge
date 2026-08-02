package app.drawbridge.herald.browser

import app.drawbridge.herald.browser.ReaderViewIntegration.ArticleReturn
import app.drawbridge.herald.browser.ReaderViewIntegration.LoadSnapshot
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.ReaderState
import mozilla.components.browser.state.state.TabSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two timing rules behind reader view in mono, both of which have been got
 * wrong in a shipped build.
 *
 * Neither is a rule about reader view as such. They are both about *when* the
 * browser's state can be believed — after `hideReaderView` has cleared the
 * reader flags but not yet moved, and after a URL has changed but before the
 * page it names exists.
 */
class ReaderViewIntegrationTest {

    private val article = "https://example.org/article"

    private fun tab(
        url: String = article,
        loading: Boolean = false,
        readerActive: Boolean = false,
    ) = TabSessionState(
        id = "tab",
        content = ContentState(url = url, loading = loading),
        readerState = ReaderState(active = readerActive),
    )

    // ArticleReturn: the second half of a back press out of reader view.

    /**
     * The trap the previous attempt fell into, in one test.
     *
     * `hideReaderView` dispatches its reader-off actions before it asks the
     * engine to step back, so the first state this sees is the article's URL,
     * reader off and nothing loading — indistinguishable from the article
     * already being back, except that it is not.
     */
    @Test
    fun `does not report the article back before the step out has begun`() {
        val watch = ArticleReturn(article)

        assertFalse(watch.isSettledOn(tab()))
    }

    @Test
    fun `does not report the article back while the step out is loading`() {
        val watch = ArticleReturn(article)
        watch.isSettledOn(tab())

        assertFalse(watch.isSettledOn(tab(loading = true)))
    }

    @Test
    fun `reports the article back once its load has finished`() {
        val watch = ArticleReturn(article)
        watch.isSettledOn(tab())
        watch.isSettledOn(tab(loading = true))

        assertTrue(watch.isSettledOn(tab()))
    }

    /**
     * Reader view closing is what the step out is waiting for; a load that ends
     * with it still showing is some other load.
     */
    @Test
    fun `does not report the article back while reader view is still showing`() {
        val watch = ArticleReturn(article)
        watch.isSettledOn(tab(loading = true, readerActive = true))

        assertFalse(watch.isSettledOn(tab(readerActive = true)))
    }

    /**
     * The step is taken up to five seconds after the press, and in that time the
     * reader may have asked for something else. Leaving *that* page would be a
     * worse bug than the one being fixed.
     */
    @Test
    fun `does not report the article back once the reader has gone elsewhere`() {
        val watch = ArticleReturn(article)
        watch.isSettledOn(tab(url = "https://example.net/", loading = true))

        assertFalse(watch.isSettledOn(tab(url = "https://example.net/")))
    }

    // LoadSnapshot: asking Gecko again whether a settled page is an article.

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
