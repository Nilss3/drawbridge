package app.drawbridge.dpc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.drawbridge.dpc.R
import app.drawbridge.dpc.policy.NoticeInbox

/**
 * Everything this project has ever said to this phone, newest first.
 *
 * **The archive half of the notice channel.** A notification is seen once and
 * swiped away; a card is dismissed and gone. Neither is somewhere to go *back*
 * to, and a message worth sending to every phone in the field is worth being
 * readable the day after it arrived — by the parent who was driving when the
 * shade lit up, or whose phone was off that week, or who simply wants to check
 * what it actually said before acting on it.
 *
 * Reached from the overflow menu on both the configuration and the lock screens,
 * which is the same reasoning as *Check for policy updates*: a locked phone is
 * the state a managed device lives in, and a screen only reachable behind the
 * key would cost a parent their key to read a message.
 *
 * Read-only by construction. There is nothing to press here except a link the
 * message itself carried, and nothing on this screen changes what the phone
 * does — which is what lets it sit outside the lock without being a way round it.
 */
class NoticesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notices)
        findViewById<View>(R.id.root).applyScreenInsets()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.noticesList)
        val empty = findViewById<TextView>(R.id.noticesEmpty)
        val entries = NoticeInbox(this).all()

        // Rebuilt from scratch on every resume rather than diffed: a notice can
        // arrive while this screen is in the background, the list is a handful
        // of rows, and there is no scroll position worth preserving on a screen
        // somebody opens to read one thing.
        list.removeAllViews()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val language = Languages.current()
        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_notice_entry, list, false)
            row.findViewById<TextView>(R.id.entryDate).text = formatDate(entry.receivedAt)
            row.findViewById<TextView>(R.id.entryTitle).text =
                entry.notice.displayTitle(language)
            row.findViewById<TextView>(R.id.entryBody).text = entry.notice.displayBody(language)
            bindLink(row.findViewById(R.id.entryLink), entry.notice.url)
            list.addView(row)
        }
    }

    /**
     * The date this phone first saw the message, in the phone's own format.
     *
     * Date without time: the hour a notice arrived is noise on a channel used a
     * handful of times, and a fleet of phones that polled at different moments
     * would show the same message at a dozen different times of day for no
     * reason anybody could act on.
     */
    private fun formatDate(millis: Long): CharSequence =
        DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

    /**
     * The message's own link, drawn only when something on this phone can open
     * it — a managed phone may have had every browser removed, and *no browser*
     * is a supported state rather than a broken one. See [NoticeCard], which
     * makes the same check for the same reason.
     */
    private fun bindLink(button: Button, url: String?) {
        val intent = url?.takeIf { it.isNotBlank() }
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

        if (intent == null || intent.resolveActivity(packageManager) == null) {
            button.visibility = View.GONE
            return
        }

        button.visibility = View.VISIBLE
        button.setOnClickListener {
            runCatching { startActivity(intent) }
                .onFailure { Log.w(TAG, "Could not open the notice's link", it) }
        }
    }

    private companion object {
        const val TAG = "NoticesActivity"
    }
}
