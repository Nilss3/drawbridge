package app.drawbridge.herald.browser

import android.view.View
import android.widget.TextView
import app.drawbridge.herald.Edition
import app.drawbridge.policy.ContentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * Holds the screen for a moment before a new page appears, in the mono edition.
 *
 * The friction is the feature: a browser that answers instantly invites the
 * reflex to open it, and the pause is there to be felt rather than to save
 * anything. It is presentation only — the page loads underneath the whole time,
 * so nothing is slower, and the wait is the same length whether the network is
 * fast or slow.
 *
 * Deliberately *not* done by delaying the load itself. `RequestInterceptor` is
 * synchronous, so waiting inside it would block the thread Gecko calls it on,
 * and the alternatives — denying the load and reissuing it, or reaching under
 * Android Components to hold GeckoView's navigation delegate — either lose form
 * submissions or take herald off the abstraction it otherwise sits cleanly on
 * top of.
 *
 * The destination host is shown while it waits, so the pause reads as the
 * browser being deliberate rather than as the browser being stuck.
 */
class LoadPauseIntegration(
    private val store: BrowserStore,
    private val overlay: View,
    private val hostLabel: TextView,
    private val sessionId: String? = null,
) : LifecycleAwareFeature {

    private var scope: CoroutineScope? = null
    private var pause: Job? = null

    /**
     * When the current suppression expires.
     *
     * Entering reader view is itself a page load — of the extension page
     * holding the stripped-down article — so without this a slow page would be
     * paused twice: once on the way in, and again when the readability check
     * finished and the article swapped itself in.
     *
     * A deadline rather than a "skip the next one" flag, because that flag went
     * stale. Reader view does not always produce a load the flag could be spent
     * on — the middleware rewrites the URL back to the article's own, so the
     * change the pause watches for may never arrive — and the flag then sat
     * there and swallowed the pause for the next page the reader actually asked
     * for. A deadline cleans up after itself.
     */
    private var suppressUntil = 0L

    override fun start() {
        if (Edition.loadDelayMillis <= 0) return
        current = this

        scope = MainScope().also { scope ->
            scope.launch {
                store.flow()
                    .map {
                        val content = it.findTabOrCustomTabOrSelectedTab(sessionId)?.content
                        Navigation(content?.url, content?.loading == true)
                    }
                    .distinctUntilChanged()
                    .collect { onNavigation(it, scope) }
            }
        }
    }

    private data class Navigation(val url: String?, val loading: Boolean)

    override fun stop() {
        pause?.cancel()
        pause = null
        scope?.cancel()
        scope = null
        overlay.visibility = View.GONE
        current = null
    }

    /**
     * Skips the pause for a load starting in the next moment — the swap into or
     * out of reader view, which is not a page anyone navigated to.
     */
    fun skipImminentPause() {
        suppressUntil = System.currentTimeMillis() + SUPPRESSION_WINDOW_MS
    }

    companion object {
        /** Set while started, so reader view can suppress its own load. */
        var current: LoadPauseIntegration? = null
            private set

        /**
         * How long a suppression lasts. Long enough to cover the reader-view
         * swap, short enough that a stale one cannot eat the pause for a page
         * the reader actually asked for.
         */
        private const val SUPPRESSION_WINDOW_MS = 1_500L
    }

    /**
     * Both halves matter, and which arrives first depends on the navigation.
     *
     * `loading` turning true is the earliest the browser knows a page was asked
     * for — before the network answers — which is where the pause has to begin.
     * Waiting for the URL instead would put it *after* the round trip, so the
     * old page would flash up first and the hold would read as the new page
     * stalling rather than as the browser being deliberate.
     *
     * The URL is still worth watching, because it arrives for navigations that
     * never flip `loading` — a restored session, a same-document change — and
     * because it is what names the destination on screen.
     */
    private fun onNavigation(navigation: Navigation, scope: CoroutineScope) {
        val url = navigation.url

        // A blank tab is not a page anyone is waiting for, and neither is the
        // extension page uBlock Origin's settings live at.
        if (url == null || !url.startsWith("http", ignoreCase = true)) {
            hide()
            return
        }

        hostLabel.text = ContentFilter.hostOf(url) ?: url

        if (System.currentTimeMillis() < suppressUntil) {
            hide()
            return
        }

        // Already holding: leave the running timer alone. Restarting it on
        // every change would turn a redirect chain into a multiple of the
        // pause — the wait belongs to the navigation, not to each hop of it.
        if (pause?.isActive == true) return

        overlay.visibility = View.VISIBLE
        pause = scope.launch {
            delay(Edition.loadDelayMillis)
            hide()
        }
    }

    private fun hide() {
        pause?.cancel()
        pause = null
        overlay.visibility = View.GONE
    }
}
