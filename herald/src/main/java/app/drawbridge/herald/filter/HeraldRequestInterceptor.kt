package app.drawbridge.herald.filter

import android.content.Context
import app.drawbridge.herald.HeraldPolicy
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor

/**
 * Blocks document loads — top-level pages and iframes — against the shared
 * policy, and replaces them with [BlockedPage].
 *
 * Subresources (images, scripts, XHR) are not routed through this callback; they
 * are handled by the bundled web extension in [BlocklistExtension]. Together the
 * two cover everything the browser loads.
 */
class HeraldRequestInterceptor(private val context: Context) : RequestInterceptor {

    private val policy by lazy { HeraldPolicy.manager(context) }

    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? {
        if (!uri.startsWith("http://", ignoreCase = true) &&
            !uri.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        if (!policy.isUrlBlocked(uri)) return null

        return RequestInterceptor.InterceptionResponse.Content(
            data = BlockedPage.create(context, uri),
            encoding = "base64",
        )
    }

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse =
        RequestInterceptor.ErrorResponse(
            ErrorPages.createUrlEncodedErrorPage(context, errorType, uri),
        )

    /**
     * Also check loads herald starts itself, so that a blocked URL typed into the
     * address bar or restored from a saved session is caught too.
     */
    override fun interceptsAppInitiatedRequests() = true
}
