package app.drawbridge.herald.filter

import android.content.Context
import app.drawbridge.herald.HeraldPolicy
import app.drawbridge.herald.browser.LoadPauseIntegration
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor

/**
 * Blocks document loads against the shared policy.
 *
 * A blocked top-level page is replaced with [BlockedPage]; a blocked iframe is
 * denied outright, because a block page shown for a frame would replace the page
 * containing it.
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

        // A blocked iframe must not take the page with it. Android Components
        // loads InterceptionResponse.Content into the *session*, not the frame
        // that asked for it, so returning a block page here replaced the whole
        // tab — one blocked tracker frame, and an article the filter was happy
        // with disappeared. Deny cancels just that frame and leaves the page.
        if (isSubframeRequest) return RequestInterceptor.InterceptionResponse.Deny

        // Mono holds the screen for a moment before a page appears. This page is
        // not one anybody is waiting for, and pausing to think on the way to a
        // wall is just a slow no. A no-op in the standard edition, which has no
        // pause to cancel.
        LoadPauseIntegration.current?.cancelForBlockedPage()

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
