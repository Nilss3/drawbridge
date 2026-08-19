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
    fun applySingleWindowPrefs() {
        if (Edition.hasTabs) return

        setPrefs(
            "browser.link.open_newwindow" to 1,
            "browser.link.open_newwindow.restriction" to 0,
        )
    }

    /**
     * In mono, makes a flick throw the page a shorter way.
     *
     * This is what replaced always-on reader view, and it is the same thesis as
     * the load pause: friction rather than stripping. What it slows is the
     * *fling* — the throw a page keeps doing after the finger has left it, which
     * is the scroll of a reflex rather than of a reader. Dragging is untouched
     * and still tracks the finger exactly, because APZ takes a drag's
     * displacement from the touch positions themselves and only the fling
     * animation reads this.
     *
     * **`apz.fling_friction` is the wrong pref, and it is the one you will reach
     * for.** It belongs to `GenericFlingAnimation`, which Android does not use:
     * here the fling is Chrome's physics, and `apz.android.chrome_fling_physics.*`
     * is what governs it. Both prefs exist, both are shipped with values in
     * GeckoView's own `geckoview-prefs.js`, and setting the wrong one succeeds,
     * reads back correctly and changes nothing at all. Measured on the API 36
     * emulator, 2026-08-19, one scripted flick on a 40,000 px page, in CSS
     * pixels scrolled — of which about 460 is the drag itself:
     *
     * | friction | scrolled | screenfuls |
     * |---|---|---|
     * | 0.015 (GeckoView's default) | 4,466 | 5.6 |
     * | 0.03 | 2,832 | 3.5 |
     * | [FLING_FRICTION] | 2,045 | 2.6 |
     * | 0.08 | 1,594 | 2.0 |
     * | 0.15 | 1,143 | 1.4 |
     *
     * `apz.max_velocity_inches_per_ms` was measured over the same run and is
     * inert: at 0.005, a fourteenth of GeckoView's own value, the flick still
     * travelled 4,382 px.
     */
    fun applySlowScrollingPrefs() {
        if (!Edition.slowScrolling) return

        setPrefs("apz.android.chrome_fling_physics.friction" to FLING_FRICTION)
    }

    /**
     * How much friction a flung page meets, against GeckoView's 0.015.
     *
     * Chosen from the table above rather than argued for: it puts a hard flick
     * at about two and a half screens instead of five and a half, which is a
     * difference nobody has to be told about and still leaves a long page
     * traversable. It is one number, and how it feels is a question for a phone.
     *
     * A float pref, which Gecko stores as a string — hence the quotes.
     */
    private const val FLING_FRICTION = "0.05"

    /**
     * Sets user-branch Gecko prefs, best-effort.
     *
     * `GeckoPreferenceController` takes a `String`, `Integer` or `Boolean`, so
     * the value type here decides the pref type; a float pref is a string pref
     * in Gecko and is set as one. A failure is logged rather than thrown for the
     * reason in [applySingleWindowPrefs]: nothing above is load-bearing on its
     * own, and an engine that ignored one of these is still a correct browser.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGeckoViewApi::class])
    private fun setPrefs(vararg prefs: Pair<String, Any>) {
        val logger = Logger("herald-engine")
        val branch = GeckoPreferenceController.PREF_BRANCH_USER
        prefs.forEach { (pref, value) ->
            val result = when (value) {
                is Int -> GeckoPreferenceController.setGeckoPref(pref, value, branch)
                else -> GeckoPreferenceController.setGeckoPref(pref, value.toString(), branch)
            }
            result.accept(
                { logger.info("$pref = $value") },
                { error -> logger.error("Could not set $pref", error) },
            )
        }
    }
}
