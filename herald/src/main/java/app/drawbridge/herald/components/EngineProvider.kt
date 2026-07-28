package app.drawbridge.herald.components

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.webcompat.WebCompatFeature
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
}
