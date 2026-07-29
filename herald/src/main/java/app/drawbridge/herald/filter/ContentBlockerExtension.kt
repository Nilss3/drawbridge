package app.drawbridge.herald.filter

import mozilla.components.concept.engine.Engine
import mozilla.components.support.base.log.logger.Logger

/**
 * uBlock Origin, installed from the APK.
 *
 * The shared blocklist ([BlocklistExtension]) and the DNS filter underneath it
 * both work at the level of a whole hostname, which is the right shape for
 * "this site is off limits" and the wrong shape for advertising: an ad served
 * from the same host as the article cannot be told apart from the article, and
 * blocking a host outright leaves the empty box behind rather than removing it.
 * uBO does the part neither can — request rules with URL and type context, and
 * cosmetic filtering to take the hole out of the page afterwards.
 *
 * It is installed as a *built-in* extension: GeckoView loads those unpacked from
 * `resource://android/assets/`, unsigned and with their permissions already
 * granted. That is deliberate on all three counts.
 *
 *  - **No network.** Installing from addons.mozilla.org would mean the browser
 *    is unfiltered on first run until a download the DNS filter has to be told
 *    to allow completes.
 *  - **No prompt.** A permission dialog on first launch, on a phone set up for
 *    someone else, is a dialog they will be asked to answer without context.
 *  - **No add-on manager.** A built-in cannot be disabled or removed from inside
 *    the browser, and herald exposes no way to install anything else. The whole
 *    extension surface is uBO's own popup and dashboard.
 *
 * The version is pinned by `tools/vendor-ublock.sh`; uBO's *filter lists* still
 * update themselves over the network as usual.
 */
class ContentBlockerExtension {

    private val logger = Logger("herald-content-blocker")

    /**
     * uBO's own pages live under this, once it is installed — the dashboard is
     * reached at `<baseUrl>dashboard.html`. Null until then.
     */
    @Volatile
    var baseUrl: String? = null
        private set

    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                baseUrl = extension.getMetadata()?.baseUrl
                logger.info("Installed uBlock Origin (${extension.getMetadata()?.version})")
            },
            onError = { error ->
                // Not fatal. Domain-level blocking still runs in the request
                // interceptor, the subresource extension and — on a managed
                // device — the DNS filter. Loud, because ad blocking silently
                // reverting to the old behaviour is exactly the kind of
                // regression nobody notices.
                logger.error("Could not install uBlock Origin", error)
            },
        )
    }

    /** The URL of uBO's settings page, or null before it has finished installing. */
    fun dashboardUrl(): String? = baseUrl?.let { it + DASHBOARD_PAGE }

    private companion object {
        const val EXTENSION_ID = "uBlock0@raymondhill.net"
        const val EXTENSION_URL = "resource://android/assets/extensions/ublock/"
        const val DASHBOARD_PAGE = "dashboard.html"
    }
}
