package app.drawbridge.herald.filter

import android.content.Context
import android.text.format.DateFormat
import android.util.Base64
import app.drawbridge.herald.DrawbridgeSelection
import app.drawbridge.herald.R
import java.util.Date

/**
 * The page shown when a load fails and drawbridge is the reason.
 *
 * ## Why this exists
 *
 * A curfew and the offline philosophy take the network away with the always-on
 * VPN's lockdown flag, which is a rule about the user rather than about a
 * network. Every app on the phone therefore sees what a broken Wi-Fi looks like,
 * and the browser said so: *server not found*, or *the connection timed out*.
 *
 * That is true and useless. The phone is not broken, nothing needs fixing, and a
 * child reading it has every reason to go and try the Wi-Fi settings — or to
 * conclude the app is faulty. herald already refuses to be vague about a blocked
 * *site*; being vague about a blocked *hour* was the one gap left, and it was
 * the more confusing of the two because it looks like a fault.
 *
 * ## How it knows
 *
 * It asks drawbridge, through the same content provider the selection comes
 * from — see [DrawbridgeSelection.connectivity]. Three answers matter: which
 * philosophy is chosen, whether traffic is refused *right now*, and when that
 * next changes.
 *
 * **Only when the phone says it is offline on purpose.** A genuine DNS failure
 * on a phone with an ordinary connection still gets the ordinary error page,
 * because the ordinary error page is right about it. Which means the check has
 * to be the narrow one: not "is drawbridge installed", not "is there a curfew
 * configured", but "is the network being refused at this moment".
 *
 * A phone without drawbridge, an older drawbridge whose provider predates the
 * columns, or a provider that throws all return null here and nothing changes.
 * The cost of being wrong is a less helpful sentence, never a page that fails to
 * appear.
 */
object OfflinePage {

    /**
     * A `data:` URL for the page, or null when this failure is not drawbridge's
     * doing.
     *
     * Null is the important half of the return type: it is what routes the load
     * back to the engine's own error page, which stays the answer for every
     * failure that really is a failure.
     *
     * **A URL and not HTML.** `RequestInterceptor.ErrorResponse` takes a `uri`
     * for the engine to load, not markup to render — the library's own
     * `ErrorPages.createUrlEncodedErrorPage` returns a `data:` URL too, which is
     * easy to miss because both are `String`. Handing it raw HTML compiles,
     * loads nothing, and leaves a blank page where the error used to be.
     */
    fun createIfOffline(context: Context, url: String?): String? {
        val state = DrawbridgeSelection(context).connectivity() ?: return null
        if (!state.offlineNow) return null

        val heading = context.getString(
            if (state.isCurfew) R.string.offline_page_curfew_heading else R.string.offline_page_offline_heading,
        )
        val message = context.getString(
            if (state.isCurfew) R.string.offline_page_curfew_message else R.string.offline_page_offline_message,
        )
        // A time rather than a duration, and only when there is one: "back at
        // 08:00" is something a person can plan around, where "in 9 hours" has
        // to be added up and is wrong a minute later. The offline philosophy has
        // no next change at all, and saying nothing is the honest version of
        // that.
        val until = state.onlineAgainAt
            ?.takeIf { state.isCurfew }
            ?.let { context.getString(R.string.offline_page_until, formatTime(context, it)) }

        val html = HeraldCard.html(
            context = context,
            title = heading,
            message = message,
            footnote = until,
            monospaceFootnote = false,
        )
        return "data:text/html;base64," +
            Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun formatTime(context: Context, epochMillis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMillis))
}
