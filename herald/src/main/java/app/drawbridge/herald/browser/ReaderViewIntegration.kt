package app.drawbridge.herald.browser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ReaderAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.readerview.ReaderViewFeature
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
 * It is asked for, in both editions. Mono used to enter it by itself on every
 * page Gecko called readerable, and that is gone — see
 * [app.drawbridge.herald.Edition.slowScrolling] for what replaced it, and
 * `docs/reader-view-back.md` for what it cost. What is left here is the toggle
 * the menu calls, and the re-check that makes the menu offer it at all.
 */
class ReaderViewIntegration(
    context: Context,
    engine: Engine,
    private val store: BrowserStore,
    private val sessionId: String? = null,
    private val controlsView: ReaderViewControlsView,
) : LifecycleAwareFeature, UserInteractionHandler {

    private val feature = ReaderViewFeature(context, engine, store, controlsView)

    private var scope: CoroutineScope? = null

    override fun start() {
        feature.start()
        toggle = this::toggle
        showControls = feature::showControls

        scope = MainScope().also { scope -> scope.launch { recheckWhenPagesFinishLoading() } }
    }

    override fun stop() {
        feature.stop()
        scope?.cancel()
        scope = null
        toggle = null
        showControls = null
    }

    /**
     * Back leaves reader view, and the page it was showing stays put.
     *
     * That is `ReaderViewFeature.onBackPressed`, unchanged: it closes the
     * controls panel if it is open, otherwise it hides reader view and reports
     * the press handled. Undoing the thing you just did is the right answer
     * wherever reader view was asked for, which since 2026-08-19 is everywhere.
     *
     * It was not always. While mono entered reader view by itself, hiding it
     * left the reader on a page they had not chosen to be on and the automatic
     * entry put the article straight back, so one press had to undo the
     * article's *two* history entries — reader view being a navigation of its
     * own. That mechanism went with the feature it was propping up; it is
     * written up in `docs/reader-view-back.md`, and the trap underneath it —
     * `goBack.invoke(null)` silently doing nothing — is worth keeping in mind
     * anywhere else.
     */
    override fun onBackPressed(): Boolean = feature.onBackPressed()

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
     * Both are now offered reader view, on the second or third asking — see
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
            feature.hideReaderView(tab)
        } else {
            LoadPauseIntegration.current?.skipImminentPause()
            feature.showReaderView(tab)
        }
    }

    companion object {
        /** Set while the integration is started, so the menu can trigger these. */
        var toggle: (() -> Unit)? = null
            private set

        var showControls: (() -> Unit)? = null
            private set

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
 * briefly points at on the way in, and offering reader view for it would load
 * the reader inside itself. Readability declines anything but http and https for
 * the same reason, so this only ever refuses what it would have refused anyway.
 */
internal fun String.isPage(): Boolean = startsWith("http", ignoreCase = true)
