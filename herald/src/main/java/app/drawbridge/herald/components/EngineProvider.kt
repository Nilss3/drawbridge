package app.drawbridge.herald.components

import android.content.Context
import app.drawbridge.herald.Edition
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.feature.webcompat.WebCompatFeature
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Owns the single [GeckoRuntime] for the process.
 *
 * Two settings here are load-bearing for the product rather than for the
 * browser, and should not be "fixed" later without understanding why:
 *
 *  - **DNS-over-HTTPS stays off.** GeckoView's TRR mode is left at its default
 *    (off), so every lookup herald makes goes through the system resolver and is
 *    therefore visible to drawbridge's DNS filter. Turning it on would let the
 *    browser resolve blocked names behind an encrypted channel the filter cannot
 *    see, and there is deliberately no setting exposed for it.
 *  - **about:config is disabled.** It is the one place a user could flip
 *    `network.trr.mode` and undo the above.
 */
object EngineProvider {

    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        context,
        GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            // Only extensions we install ourselves; nothing web-initiated.
            .extensionsWebAPIEnabled(false)
            .consoleOutput(false)
            .build(),
    ).also { runtime = it }

    fun createEngine(context: Context, defaultSettings: DefaultSettings): Engine =
        GeckoEngine(context, defaultSettings, getOrCreateRuntime(context)).also {
            WebCompatFeature.install(it)
        }

    fun createClient(context: Context): Client =
        GeckoViewFetchClient(context, getOrCreateRuntime(context))

    /**
     * In mono, makes Gecko itself open `target="_blank"` and `window.open()` in
     * the page you are already on.
     *
     * Removing the tab UI is not enough on its own: without this, every such
     * link still asks for a new window, and the app has to either grant it — a
     * tab nobody can see or close, since there is no tray — or refuse it, and a
     * link that does nothing at all is worse than a tab. Handling it one layer
     * down means there is no window request to field in the first place.
     *
     * `browser.link.open_newwindow` = 1 is "current tab"; the `.restriction` = 0
     * applies that even to `window.open` calls that ask for window features,
     * which is the case scripts use to force a popup.
     *
     * Best-effort: a failure here costs the odd stray tab, not correctness, so
     * it is logged rather than thrown.
     *
     * `GeckoPreferenceController` is marked experimental, and opting in is a
     * considered choice rather than a way past the lint error. Nothing depends
     * on it: if a GeckoView upgrade changes or withdraws the API this stops
     * compiling, and if it silently stopped working at runtime the single-tab
     * invariant in `enforceSingleTab` still collapses whatever tab the window
     * request produced. The pref makes links behave properly; the invariant is
     * what makes mono correct.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGeckoViewApi::class])
    fun applySingleWindowPrefs() {
        if (Edition.hasTabs) return

        val logger = Logger("herald-engine")
        listOf(
            "browser.link.open_newwindow" to 1,
            "browser.link.open_newwindow.restriction" to 0,
        ).forEach { (pref, value) ->
            GeckoPreferenceController
                .setGeckoPref(pref, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept(
                    { logger.info("$pref = $value") },
                    { error -> logger.error("Could not set $pref", error) },
                )
        }
    }
}
