package app.drawbridge.dpc.policy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.drawbridge.dpc.R
import app.drawbridge.dpc.ui.NoticesActivity

/**
 * Puts a notice in the phone's notification shade.
 *
 * **Because anything inside the app reaches nobody.** The first version of this
 * feature drew a card on drawbridge's own screens, which is only seen by
 * somebody who opens drawbridge — and the whole shape of this product is that
 * they should not have to. A phone that has been set up and locked is meant to
 * be left alone, so the configuration screen may go unopened for months and the
 * lock screen only when somebody is unlocking. A channel that can only be read
 * by people who were already looking is not a channel, and the card is gone.
 *
 * **Raised once per notice, at the poll that first sees it**, and never again —
 * see [NoticeInbox.record], which is what decides. A notification re-posted on
 * every refresh would be the same message three times a day, which is how a
 * channel meant for a handful of messages in a product's life becomes one
 * nobody reads.
 *
 * Tapping it opens [NoticesActivity] rather than the message itself, because
 * that screen holds this message *and* the ones before it, and somebody who has
 * not looked in a while should land on all of them.
 */
object NoticeNotifier {

    fun notify(context: Context, entry: NoticeInbox.Received) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)

        // The app's own language, like the notices screen: this text sits beside
        // drawbridge's other words, and the shade is no reason to change which
        // language the parent reads.
        val language = app.drawbridge.dpc.ui.Languages.current()

        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, NoticesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(entry.notice.displayTitle(language))
            .setContentText(entry.notice.displayBody(language))
            // The body is a paragraph and the shade shows one line collapsed,
            // so without this the message is a message the parent cannot read.
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(entry.notice.displayBody(language)),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // **A missing POST_NOTIFICATIONS grant throws nothing and shows
        // nothing.** From API 33 the post is silently dropped if the runtime
        // permission was refused, so this is logged rather than assumed. The
        // message is not lost when that happens — it is filed, and the notices
        // screen lists it — but nobody is told it arrived, which is the whole
        // point, so the log line is the only trace anybody could follow.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onSuccess { Log.i(TAG, "Raised a notification for notice ${entry.notice.id}") }
            .onFailure { Log.w(TAG, "Could not raise a notification", it) }
    }

    /**
     * **DEFAULT importance, which is a deliberate middle.** LOW would put the
     * message in the shade without a sound, where it would sit under the
     * filter's own permanent notification and be read by nobody — the exact
     * failure this class exists to fix. HIGH would take over the screen, and
     * nothing sent here is an emergency: it is a project telling somebody about
     * a fix or a change, which is worth a sound and is not worth an interruption.
     *
     * The channel is the parent's to change afterwards, which is how it should
     * be. Android will not let the importance be raised again once created, and
     * there is no reason to want to.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notice_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notice_channel_description)
            },
        )
    }

    private const val TAG = "NoticeNotifier"
    private const val CHANNEL_ID = "drawbridge-notices"

    /**
     * One id for every notice, so a second message replaces the first in the
     * shade rather than stacking. They lead to the same screen, which lists both.
     */
    private const val NOTIFICATION_ID = 2
    private const val REQUEST_CODE = 0x0E0F
}
