package app.drawbridge.herald.browser

import android.view.View
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.findinpage.FindInPageFeature
import mozilla.components.feature.findinpage.view.FindInPageView
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/**
 * Wires the find-in-page bar to the selected tab, and exposes a launcher the
 * browser menu can call.
 */
class FindInPageIntegration(
    private val store: BrowserStore,
    private val sessionId: String? = null,
    private val view: FindInPageView,
    private val engineView: EngineView,
) : LifecycleAwareFeature, UserInteractionHandler {

    private val feature = FindInPageFeature(store, view, engineView) { onClose() }

    override fun start() {
        feature.start()
        launch = this::launch
    }

    override fun stop() {
        feature.stop()
        launch = null
    }

    override fun onBackPressed(): Boolean = feature.onBackPressed()

    private fun launch() {
        val tab = store.state.findCustomTabOrSelectedTab(sessionId) ?: return
        (view as View).visibility = View.VISIBLE
        feature.bind(tab)
    }

    private fun onClose() {
        (view as View).visibility = View.GONE
    }

    companion object {
        /** Set while the integration is started, so the menu can trigger it. */
        var launch: (() -> Unit)? = null
            private set
    }
}
