package app.drawbridge.herald.browser

import android.content.Context
import android.view.View
import app.drawbridge.herald.Edition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.action.ReaderAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.readerview.ReaderViewFeature
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.readerview.view.ReaderViewControlsView
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/**
 * Reader view: strips a page down to its article text, and exposes a toggle the
 * browser menu can call.
 *
 * Reader view is Gecko's own — the same Readability pass Firefox uses, shipped as
 * a built-in web extension inside `feature-readerview`. It runs entirely on the
 * page already loaded, so it fetches nothing and reaches nothing the filter has
 * not already allowed through.
 *
 * In the mono edition it also enters itself, wherever Gecko reports a page as
 * readerable — see [Edition.autoReaderView].
 */
class ReaderViewIntegration(
    context: Context,
    engine: Engine,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val sessionId: String? = null,
    private val controlsView: ReaderViewControlsView,
) : LifecycleAwareFeature, UserInteractionHandler {

    private val feature = ReaderViewFeature(context, engine, store, controlsView)

    private var scope: CoroutineScope? = null

    /**
     * Set when the reader turns reader view *off* on a page, so mono's
     * automatic entry does not immediately put it back. Cleared on navigation:
     * the refusal belongs to the page it was made on, not to the browser.
     */
    private var dismissedForPage = false

    override fun start() {
        feature.start()
        toggle = this::toggle
        showControls = feature::showControls

        scope = MainScope().also { scope ->
            scope.launch { recheckWhenPagesFinishLoading() }
            if (Edition.autoReaderView) scope.launch { enterOnReaderablePages() }
        }
    }

    override fun stop() {
        feature.stop()
        scope?.cancel()
        scope = null
        toggle = null
        showControls = null
    }

    /**
     * Back goes back, and in mono that means leaving the page rather than
     * leaving reader view.
     *
     * `ReaderViewFeature.onBackPressed` hides reader view and reports the press
     * handled. That is right where reader view was asked for — it undoes the
     * thing you just did — and wrong in mono, where nobody asked for it: reader
     * view is simply how pages look, so peeling it off costs a press that
     * appears to do nothing and leaves you where you already were.
     *
     * Skipping it is not enough either, because showing reader view is a
     * *navigation*: the article and the reader's rendering of it are two history
     * entries, so a plain back lands on the article and the automatic entry puts
     * the reader straight back on. One press therefore has to undo both, which
     * is what [leavePageWhenArticleIsBack] finishes — `hideReaderView` steps out
     * of the reader's own entry, and the second step leaves the article.
     *
     * The controls panel is the exception. It *is* something the reader opened,
     * so back closes it and means nothing else.
     */
    override fun onBackPressed(): Boolean {
        if (controlsVisible()) return feature.onBackPressed()

        val tab = tab() ?: return false
        if (!tab.readerState.active) return false

        if (Edition.autoReaderView) {
            // Set before hiding: hiding flips the state this watches, and the
            // collector must not read a stale flag and re-enter reader view.
            dismissedForPage = true
            val article = tab.content.url
            feature.hideReaderView(tab)
            scope?.launch { leavePageWhenArticleIsBack(article) }
            return true
        }

        val handled = feature.onBackPressed()
        // Remember the dismissal, or automatic entry would put it straight back.
        if (handled) dismissedForPage = true
        return handled
    }

    /**
     * The second half of a back press out of reader view: once the browser is
     * back on the plain article, step off that too.
     *
     * Two things had to be right, and the earlier attempt at this had neither.
     *
     * **The step goes to a named tab.** `GoBackUseCase.invoke` defaults its tab
     * to the selected one, but an explicit `null` — which is what [sessionId] is
     * outside a custom tab — makes it return without dispatching anything. So
     * the previous attempt's second step was never taken in any ordinary
     * session, however correctly the rest of the mechanism ran. It is the whole
     * of "back goes to the plain page and stops there".
     *
     * **And it waits for the load, not for the position.** Stepping out of the
     * reader's entry moves the history index and reports `canGoForward` about
     * four hundred milliseconds before the article is actually back. `loading`
     * turning false is the point where a second step is reliably taken —
     * measured at roughly 700 ms after the press, and accepted first time in
     * every run once the tab was named.
     *
     * The cost is that the plain article shows for that moment on the way past.
     * A flicker is worth more than a wrong destination, and anything earlier is
     * a race of the kind this bug has already been "fixed" by twice.
     *
     * Bounded three ways, because a step this delayed must not fire into a page
     * the reader has since asked for: it gives up after [RETREAT_TIMEOUT_MS], it
     * only acts while the URL is still the article's, and it takes no step where
     * there is nothing behind the article — a page opened from another app has
     * no history to leave, and stopping on it is then right rather than a
     * failure.
     */
    private suspend fun leavePageWhenArticleIsBack(article: String) {
        val watch = ArticleReturn(article)
        val landed = withTimeoutOrNull(RETREAT_TIMEOUT_MS) {
            store.flow()
                .mapNotNull { state -> currentTab(state.selectedTabId) }
                .first(watch::isSettledOn)
        } ?: return

        if (landed.content.canGoBack) sessionUseCases.goBack.invoke(landed.id)
    }

    private fun controlsVisible(): Boolean = controlsView.asView().visibility == View.VISIBLE

    /**
     * Enters reader view by itself once Gecko reports the page as readerable.
     *
     * `readerable` is not known at load time — it is the answer to a check that
     * runs over the finished DOM and comes back over native messaging — so this
     * waits for it rather than trying at navigation.
     */
    private suspend fun enterOnReaderablePages() {
        store.flow()
            .map { state -> currentTab(state.selectedTabId)?.let(::ReaderSnapshot) }
            .distinctUntilChanged()
            .collect { snapshot -> onReaderStateChanged(snapshot) }
    }

    private fun onReaderStateChanged(snapshot: ReaderSnapshot?) {
        if (snapshot == null || !snapshot.url.isPage()) return

        if (snapshot.url != lastUrl) {
            lastUrl = snapshot.url
            dismissedForPage = false
        }

        // Not while a navigation is in flight. The readability answer names no
        // page — it is a bare boolean against the tab — so one that arrives
        // during a load is an answer about the page being left, and entering
        // reader view on it is itself a navigation: it loads the old article's
        // reader page over the one that was asked for, and the reader is pulled
        // back to where they just were. The re-check below makes late answers
        // ordinary, so this guard is what keeps them harmless.
        if (!snapshot.readerable || snapshot.active || snapshot.loading || dismissedForPage) return

        val tab = tab() ?: return
        // Entering reader view loads the extension's article page. That is not
        // a page the reader asked for, so it does not get its own pause.
        LoadPauseIntegration.current?.skipImminentPause()
        feature.showReaderView(tab)
    }

    private var lastUrl: String? = null

    /**
     * Asks Gecko again, once a page has settled, whether it is an article.
     *
     * Readability is a single question put to a content script, and
     * `ReaderViewMiddleware` asks it the moment the URL changes — before the
     * page it is asking about exists. Whichever port happens to be connected
     * then answers: the outgoing document, or the incoming one before it has
     * been laid out. Readability decides by measuring rendered paragraphs, so a
     * page asked too early answers *no* about itself; `checkRequired` is then
     * cleared whether or not anything was there to answer, and nothing asks
     * again.
     *
     * That is the whole of "reader view does not always trigger". Measured on
     * two Wikipedia articles that score comfortably above Readability's own
     * threshold once their DOM has settled: herald called both of them not
     * readerable, one of them every time and the other about half the time,
     * entirely according to how fast the page came out of the cache.
     *
     * Both now enter reader view, on the second or third asking — see
     * [askWhetherItIsAnArticle] for why one more question is not enough.
     */
    private suspend fun recheckWhenPagesFinishLoading() {
        store.flow()
            .map { state -> currentTab(state.selectedTabId)?.let(::LoadSnapshot) }
            .distinctUntilChanged()
            .collect { snapshot -> onLoadStateChanged(snapshot) }
    }

    private fun onLoadStateChanged(snapshot: LoadSnapshot?) {
        val previous = lastLoad
        lastLoad = snapshot
        if (snapshot == null || !snapshot.endsALoad(previous)) return

        recheck?.cancel()
        recheck = scope?.launch { askWhetherItIsAnArticle(snapshot) }
    }

    /**
     * Puts the readability question to the page a few times over the couple of
     * seconds after its load ends, and stops as soon as it is answered.
     *
     * Asking once would be enough if there were a moment that meant "the page
     * can be measured now", and there is not. `loading` turning false is the
     * closest thing the browser state has, and on a page served from Gecko's
     * cache it arrives about fifty milliseconds after the navigation — before
     * the document exists to be measured. `firstContentfulPaint` and `progress`
     * are worse: both were already carrying the *previous* page's values at
     * that point in every trace.
     *
     * So it asks again. Measured on two Wikipedia articles that herald reported
     * as not readerable — one of them every single time — the answer came back
     * yes on the ask seven hundred milliseconds after the load ended, and never
     * on the one at the load's end.
     *
     * The cost is bounded and small: at most [RECHECK_ATTEMPTS] messages to a
     * content script, only while the page is still an open question, and only
     * for as long as the reader stays on it.
     */
    private suspend fun askWhetherItIsAnArticle(page: LoadSnapshot) {
        repeat(RECHECK_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RECHECK_INTERVAL_MS)

            val tab = tab() ?: return
            // The reader has moved on, or is moving: stop asking about a page
            // that is no longer the one on screen.
            if (tab.id != page.tabId || tab.content.url != page.url) return
            if (tab.content.loading) return
            if (tab.readerState.readerable || tab.readerState.active) return

            store.dispatch(ReaderAction.UpdateReaderableCheckRequiredAction(tab.id, true))
        }
    }

    private var recheck: Job? = null

    private var lastLoad: LoadSnapshot? = null

    private fun currentTab(selectedTabId: String?): TabSessionState? =
        sessionId?.let { store.state.findTab(it) }
            ?: selectedTabId?.let { store.state.findTab(it) }

    private fun tab(): TabSessionState? =
        sessionId?.let { store.state.findTab(it) } ?: store.state.selectedTab

    /** The parts of a tab this cares about, so the flow only wakes on those. */
    private data class ReaderSnapshot(
        val url: String,
        val loading: Boolean,
        val readerable: Boolean,
        val active: Boolean,
    ) {
        constructor(tab: TabSessionState) : this(
            url = tab.content.url,
            loading = tab.content.loading,
            readerable = tab.readerState.readerable,
            active = tab.readerState.active,
        )
    }

    /** The same, for the re-check: a page's load ending, and nothing else. */
    internal data class LoadSnapshot(
        val tabId: String,
        val url: String,
        val loading: Boolean,
        val active: Boolean,
    ) {
        constructor(tab: TabSessionState) : this(
            tabId = tab.id,
            url = tab.content.url,
            loading = tab.content.loading,
            active = tab.readerState.active,
        )

        /**
         * Whether this snapshot is the far edge of a load worth asking about
         * again — the same tab, done loading, having been loading a moment ago.
         *
         * Edge-triggered rather than level-triggered: `loading` staying false is
         * the ordinary state of a browser, and re-asking on every update would
         * put a message on the wire for every scroll and title change.
         */
        fun endsALoad(previous: LoadSnapshot?): Boolean =
            previous != null &&
                previous.tabId == tabId &&
                previous.loading &&
                !loading &&
                !active &&
                url.isPage()
    }

    private fun toggle() {
        // Reader state lives on TabSessionState, so this needs the tab itself
        // rather than the SessionState the custom-tab selectors return.
        val tab = tab() ?: return
        if (tab.readerState.active) {
            // Remember the refusal, or mono would turn it straight back on.
            dismissedForPage = true
            feature.hideReaderView(tab)
        } else {
            LoadPauseIntegration.current?.skipImminentPause()
            feature.showReaderView(tab)
        }
    }

    /**
     * Watches the browser come back to an article after reader view has been
     * told to close on it.
     *
     * The state it keeps is one bit, and that bit is the point.
     * `hideReaderView` clears the reader flags *before* it asks the engine to
     * step out of the reader's history entry, so the moment this starts
     * watching, the tab already looks exactly like the article settled and
     * ready — right URL, reader off, nothing loading. Acting on that would put
     * the second step back where the previous attempt had it, milliseconds
     * after the press and against a traversal that has not begun. Waiting for a
     * load to have started first is what makes "settled" mean the article
     * rather than the reader page it is still standing on.
     */
    internal class ArticleReturn(private val article: String) {

        private var loadStarted = false

        fun isSettledOn(tab: TabSessionState): Boolean {
            if (tab.content.loading) loadStarted = true

            return loadStarted &&
                !tab.content.loading &&
                !tab.readerState.active &&
                tab.content.url == article
        }
    }

    companion object {
        /** Set while the integration is started, so the menu can trigger these. */
        var toggle: (() -> Unit)? = null
            private set

        var showControls: (() -> Unit)? = null
            private set

        /**
         * How long the second half of a back press waits for the article to
         * come back before giving up on it.
         *
         * Generous, because it is waiting on a page load, and harmless if
         * exceeded: the press has already left reader view, so giving up leaves
         * the reader on the plain article rather than anywhere unexpected.
         */
        private const val RETREAT_TIMEOUT_MS = 5_000L

        /**
         * How many times a settled page is asked whether it is an article, and
         * how long apart. Between them they cover the couple of seconds after a
         * load in which a page becomes measurable.
         */
        private const val RECHECK_ATTEMPTS = 4
        private const val RECHECK_INTERVAL_MS = 700L
    }
}

/**
 * Whether this is a page the reader is looking at, rather than one of the
 * browser's own.
 *
 * Reader view's rendering of an article is a `moz-extension:` page that the URL
 * briefly points at on the way in, and it is neither something to offer reader
 * view for — that would load the reader inside itself — nor something to measure
 * a dismissal against. Readability declines anything but http and https for the
 * same reason, so this only ever refuses what it would have refused anyway.
 */
internal fun String.isPage(): Boolean = startsWith("http", ignoreCase = true)
