package app.drawbridge.herald.browser

import android.content.Context
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.readerview.ReaderViewFeature
import mozilla.components.feature.readerview.view.ReaderViewControlsView
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
 */
class ReaderViewIntegration(
    context: Context,
    engine: Engine,
    private val store: BrowserStore,
    private val sessionId: String? = null,
    controlsView: ReaderViewControlsView,
) : LifecycleAwareFeature, UserInteractionHandler {

    private val feature = ReaderViewFeature(context, engine, store, controlsView)

    override fun start() {
        feature.start()
        toggle = this::toggle
        showControls = feature::showControls
    }

    override fun stop() {
        feature.stop()
        toggle = null
        showControls = null
    }

    override fun onBackPressed(): Boolean = feature.onBackPressed()

    private fun toggle() {
        // Reader state lives on TabSessionState, so this needs the tab itself
        // rather than the SessionState the custom-tab selectors return.
        val tab = sessionId?.let { store.state.findTab(it) } ?: store.state.selectedTab ?: return
        if (tab.readerState.active) feature.hideReaderView(tab) else feature.showReaderView(tab)
    }

    companion object {
        /** Set while the integration is started, so the menu can trigger these. */
        var toggle: (() -> Unit)? = null
            private set

        var showControls: (() -> Unit)? = null
            private set
    }
}
