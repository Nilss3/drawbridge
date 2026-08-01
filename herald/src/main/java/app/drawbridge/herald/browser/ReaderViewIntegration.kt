package app.drawbridge.herald.browser

import android.content.Context
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
    private val sessionId: String? = null,
    controlsView: ReaderViewControlsView,
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

        if (Edition.autoReaderView) startAutoEntry()
    }

    override fun stop() {
        feature.stop()
        scope?.cancel()
        scope = null
        toggle = null
        showControls = null
    }

    /**
     * Back leaves reader view, and — in mono — has to be remembered as a
     * dismissal or it does not appear to leave at all.
     *
     * `ReaderViewFeature.onBackPressed` hides reader view and reports that it
     * handled the press. In mono that put a readerable page with reader view off
     * in front of [onReaderStateChanged], which is exactly the condition it
     * turns reader view *on* for — so the article came straight back and the
     * button looked dead. A dismissal is a dismissal however it is made.
     */
    override fun onBackPressed(): Boolean {
        val leavingReaderView = tab()?.readerState?.active == true
        val handled = feature.onBackPressed()
        if (handled && leavingReaderView) dismissedForPage = true
        return handled
    }

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
    ) {
        constructor(tab: TabSessionState) : this(
            url = tab.content.url,
            readerable = tab.readerState.readerable,
            active = tab.readerState.active,
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
