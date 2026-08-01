package app.drawbridge.herald.browser

import android.content.Context
import android.view.View
import app.drawbridge.herald.Edition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    /**
     * Set while a back press is half done: reader view has been told to close
     * and the history step out of the article is still owed.
     */
    private var goBackWhenReaderCloses = false

    override fun start() {
        feature.start()
        toggle = this::toggle
        showControls = feature::showControls

        if (Edition.autoReaderView) startAutoEntry()
    }

    override fun stop() {
        feature.stop()
        scope?.cancel()
        scope = null
        goBackWhenReaderCloses = false
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
     * *navigation*: the article and its reader view are two history entries, so
     * a plain back lands on the article and the automatic entry puts the reader
     * straight back on. One press therefore has to undo both, which is what
     * [goBackWhenReaderCloses] finishes — `hideReaderView` retreats out of the
     * reader's own entry, and the second step leaves the article.
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
            goBackWhenReaderCloses = tab.content.canGoBack
            feature.hideReaderView(tab)
            return true
        }

        val handled = feature.onBackPressed()
        // Remember the dismissal, or automatic entry would put it straight back.
        if (handled) dismissedForPage = true
        return handled
    }

    private fun controlsVisible(): Boolean = controlsView.asView().visibility == View.VISIBLE

    /**
     * Enters reader view by itself once Gecko reports the page as readerable.
     *
     * `readerable` is not known at load time — it is the answer to a check that
     * runs over the finished DOM and comes back over native messaging — so this
     * waits for it rather than trying at navigation.
     */
    private fun startAutoEntry() {
        scope = MainScope().also { scope ->
            scope.launch {
                store.flow()
                    .map { state -> currentTab(state.selectedTabId)?.let(::ReaderSnapshot) }
                    .distinctUntilChanged()
                    .collect { snapshot -> onReaderStateChanged(snapshot) }
            }
        }
    }

    private fun onReaderStateChanged(snapshot: ReaderSnapshot?) {
        if (snapshot == null) return

        // The second half of a back press out of reader view; see onBackPressed.
        //
        // The signal is `canGoForward`, not the reader flag. `hideReaderView`
        // clears that flag *before* it asks the engine to retreat, so acting on
        // it fired the second step six milliseconds later, while the engine was
        // still on the reader's entry — and two `goBack`s issued against the
        // same position move one place between them. Reader view is always the
        // newest entry when it enters by itself, so nothing can go forward from
        // it; the moment something can, the retreat has landed.
        if (goBackWhenReaderCloses && !snapshot.active) {
            if (!snapshot.canGoForward) return
            goBackWhenReaderCloses = false
            sessionUseCases.goBack.invoke(sessionId)
            return
        }

        if (snapshot.url != lastUrl) {
            lastUrl = snapshot.url
            dismissedForPage = false
        }

        if (!snapshot.readerable || snapshot.active || dismissedForPage) return

        val tab = tab() ?: return
        // Entering reader view loads the extension's article page. That is not
        // a page the reader asked for, so it does not get its own pause.
        LoadPauseIntegration.current?.skipImminentPause()
        feature.showReaderView(tab)
    }

    private var lastUrl: String? = null

    private fun currentTab(selectedTabId: String?): TabSessionState? =
        sessionId?.let { store.state.findTab(it) }
            ?: selectedTabId?.let { store.state.findTab(it) }

    private fun tab(): TabSessionState? =
        sessionId?.let { store.state.findTab(it) } ?: store.state.selectedTab

    /** The parts of a tab this cares about, so the flow only wakes on those. */
    private data class ReaderSnapshot(
        val url: String,
        val readerable: Boolean,
        val active: Boolean,
        val canGoForward: Boolean,
    ) {
        constructor(tab: TabSessionState) : this(
            url = tab.content.url,
            readerable = tab.readerState.readerable,
            active = tab.readerState.active,
            canGoForward = tab.content.canGoForward,
        )
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

    companion object {
        /** Set while the integration is started, so the menu can trigger these. */
        var toggle: (() -> Unit)? = null
            private set

        var showControls: (() -> Unit)? = null
            private set
    }
}
