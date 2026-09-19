package app.drawbridge.dpc.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.drawbridge.dpc.R
import app.drawbridge.dpc.policy.NoticeInbox
import app.drawbridge.policy.model.PolicyNotice

/**
 * Draws the policy's notice, on whichever screen asked.
 *
 * One renderer rather than one per screen, for the reason `item_notice.xml`
 * gives: the configuration screen and the lock screen must not be able to say
 * different things. Both call [render] from their own render pass; this decides
 * whether there is anything to draw.
 *
 * **It draws nothing almost always**, which is the intended resting state and
 * the reason this is a few lines rather than a component.
 */
object NoticeCard {

    /**
     * Shows or hides the card on [activity], which must have included
     * `item_notice.xml`.
     *
     * @param notice the document's notice, or null when it carries none.
     * @param onDismissed called after the parent dismisses, so a screen that
     *   keeps other state in step can re-render. Dismissal is already recorded
     *   and the card already hidden by the time this runs.
     */
    fun render(
        activity: Activity,
        notice: PolicyNotice?,
        onDismissed: () -> Unit = {},
    ) {
        val card = activity.findViewById<View>(R.id.noticeCard) ?: return
        val inbox = NoticeInbox(activity)

        if (notice == null || !inbox.shouldShow(notice)) {
            card.visibility = View.GONE
            return
        }

        // The app's own language rather than the phone's: this card sits among
        // drawbridge's own words, which follow the picker on the configuration
        // screen, and a notice in a fourth language would be the only thing on
        // the screen that ignored it. Falls back to the untranslated original
        // when the document has no variant, which is what `pick` is for.
        val language = Languages.current()

        activity.findViewById<TextView>(R.id.noticeTitle).text = notice.displayTitle(language)
        activity.findViewById<TextView>(R.id.noticeBody).text = notice.displayBody(language)

        activity.findViewById<Button>(R.id.noticeDismiss).setOnClickListener {
            inbox.dismiss(notice)
            card.visibility = View.GONE
            onDismissed()
        }

        renderLink(activity, notice)
        card.visibility = View.VISIBLE
    }

    /**
     * The *read more* button, drawn only when pressing it would do something.
     *
     * Two conditions, and the second is the one that matters on this product: a
     * managed phone may have had every browser removed, and a chooser set to
     * *no browser* is a supported state rather than a broken one. A button that
     * threw `ActivityNotFoundException` on such a phone would turn a message
     * into a crash, so the intent is resolved before the button is offered.
     *
     * A url that resolves is still not a url that loads: it goes through the
     * filter like any other, so a host the blocklists refuse shows a block page,
     * and during a curfew the page simply fails. Both are the phone working, and
     * neither is worth a second check here.
     */
    private fun renderLink(activity: Activity, notice: PolicyNotice) {
        val button = activity.findViewById<Button>(R.id.noticeLink)
        val url = notice.url?.takeIf { it.isNotBlank() }
        val intent = url?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

        if (intent == null || intent.resolveActivity(activity.packageManager) == null) {
            button.visibility = View.GONE
            return
        }

        button.visibility = View.VISIBLE
        button.setOnClickListener {
            runCatching { activity.startActivity(intent) }
                .onFailure { Log.w(TAG, "Could not open the notice's link", it) }
        }
    }

    private const val TAG = "NoticeCard"
}
