package app.drawbridge.herald.browser

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import app.drawbridge.herald.Edition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.geckoview.GeckoView

/**
 * Draws every page without colour, in the mono edition.
 *
 * The filter sits on the engine's own surface rather than on the page, which is
 * the whole reason this works as well as it does:
 *
 *  - It reaches everything Gecko paints — text, images, CSS, canvas, WebGL and
 *    **playing video** — rather than only what a stylesheet can select.
 *  - It has no layout semantics. The obvious alternative, injecting
 *    `filter: grayscale(1)` through a content script, makes the root element a
 *    containing block for `position: fixed` descendants, so sticky headers and
 *    fixed navigation start scrolling with the page on a great many sites.
 *  - A page cannot see it or remove it; there is no injected stylesheet for
 *    page script to find.
 *
 * The cost is that GeckoView has to be moved off its default SurfaceView, whose
 * contents the system composites separately and which a view-level colour
 * filter therefore cannot touch. On a TextureView the engine renders into the
 * app's own view hierarchy, where the filter applies. That is a less-travelled
 * GeckoView path and it copies a frame more than SurfaceView does, so it is
 * worth watching on real hardware rather than an emulator.
 */
class GreyscaleIntegration(
    private val store: BrowserStore,
    private val engineView: EngineView,
    /**
     * Chrome that has to be drained too, because a palette cannot reach it: the
     * toolbar holds uBlock Origin's browser-action icon, which the extension
     * supplies as a coloured bitmap. Left alone it is the one bright thing on
     * screen. Unlike the page, this never goes back to colour — "show in
     * colour" is granted to a page, not to the browser.
     */
    private val chrome: List<View> = emptyList(),
    private val sessionId: String? = null,
) : LifecycleAwareFeature {

    private val logger = Logger("herald-greyscale")
    private var scope: CoroutineScope? = null

    /**
     * Set while the current page is showing in colour because someone asked for
     * it. Cleared on navigation — see [start].
     */
    private var colourRestored = false

    private val filter = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    override fun start() {
        if (!Edition.greyscale) return
        current = this

        val gecko = geckoView()
        if (gecko == null) {
            // Not fatal, but mono without this is just herald, so it should be
            // obvious in a log rather than merely look wrong.
            logger.error("No GeckoView to greyscale; pages will render in colour")
            return
        }

        gecko.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        chrome.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, filter) }
        apply()

        scope = MainScope().also { scope ->
            // Colour is granted to a page, not to the browser: navigating away
            // takes it back. Keyed on the URL for the same reason the reader
            // view's check is — it is the one signal that means "different
            // page", including redirects.
            scope.launch {
                store.flow()
                    .map { it.findTabOrCustomTabOrSelectedTab(sessionId)?.content?.url }
                    .distinctUntilChanged()
                    .collect {
                        if (colourRestored) {
                            colourRestored = false
                            apply()
                        }
                    }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        if (current === this) current = null
    }

    /** True when the current page has been put back into colour. */
    val isColourRestored: Boolean get() = colourRestored

    /**
     * Shows the current page in colour until it navigates away — for the graph
     * or map that cannot be read without it.
     */
    fun restoreColour() {
        if (!Edition.greyscale) return
        colourRestored = true
        apply()
    }

    private fun apply() {
        val gecko = geckoView() ?: return
        val grey = !colourRestored
        // Both the GeckoView and the TextureView it renders into: which one
        // carries the layer has varied across GeckoView versions, and setting
        // both is cheap and harmless.
        (gecko.children() + gecko).forEach { view ->
            if (grey) {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, filter)
            } else {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }

    private fun geckoView(): GeckoView? =
        (engineView.asView() as? ViewGroup)?.children()?.filterIsInstance<GeckoView>()?.firstOrNull()

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    companion object {
        /**
         * Set while the integration is started, so the menu can reach it —
         * the same shape [ReaderViewIntegration] uses for its toggle.
         */
        var current: GreyscaleIntegration? = null
            private set
    }
}
