package app.drawbridge.herald.filter

import android.content.Context
import app.drawbridge.herald.HeraldPolicy
import app.drawbridge.herald.R

/**
 * The page shown instead of blocked content.
 *
 * Built as a self-contained string rather than fetched, so it renders with no
 * network and no assets. The card itself is [HeraldCard], shared with
 * [OfflinePage] so the two pages a filtered phone sees most cannot drift apart.
 *
 * The blocked host is repeated in the page body because the address bar shows
 * the `data:` URL the engine loaded, not the site that was requested.
 */
object BlockedPage {

    fun create(context: Context, url: String): String {
        val browser = HeraldPolicy.manager(context).policy.value.browser

        // The language herald itself resolved to, not the phone's raw setting:
        // it is whatever picked values-nl or values-fr, so the heading from the
        // document and the strings around it cannot disagree.
        val language = context.resources.configuration.locales[0].language

        val title = browser.displayBlockedPageTitle(language)
            .ifBlank { context.getString(R.string.blocked_page_heading) }
        val host = app.drawbridge.policy.ContentFilter.hostOf(url) ?: url

        return HeraldCard.html(
            context = context,
            title = title,
            message = browser.displayBlockedPageMessage(language),
            footnote = host,
            monospaceFootnote = true,
        )
    }
}
