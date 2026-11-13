package app.drawbridge.dpc.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.drawbridge.dpc.R
import app.drawbridge.dpc.ui.UpdateActivity
import app.drawbridge.policy.model.AppUpdate

/**
 * Tells the parent, in the notification shade, that a drawbridge update is
 * waiting for them.
 *
 * **Only for an update drawbridge could not install by itself.** Since 0.2.40
 * [UpdateWorker] installs drawbridge's own update unattended, once per version,
 * and when that works there is nothing to say. When a poll later finds the same
 * version still waiting — the phone refused it, the process died mid-install,
 * the session never reported back — the only route left is the Update screen,
 * and a locked phone's drawbridge screens go unopened for months. The button on
 * the configuration and lock screens reaches nobody who is not already looking,
 * which is the lesson [app.drawbridge.dpc.policy.NoticeNotifier] was built on.
 *
 * **Once per version.** A notification re-posted every three hours for the same
 * update is how a channel gets muted.
 */
object UpdateNotifier {

    fun notifyWaiting(context: Context, update: AppUpdate) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED_VERSION, 0) >= update.versionCode) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)

        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, UpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = context.getString(R.string.update_waiting_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_waiting_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Marked whether or not the post is shown: a refused POST_NOTIFICATIONS
        // drops it silently, and retrying every poll changes nothing about that.
        // The Update button on drawbridge's own screens still says it.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onSuccess { Log.i(TAG, "Raised a notification for drawbridge ${update.versionCode}") }
            .onFailure { Log.w(TAG, "Could not raise the update notification", it) }
        prefs.edit().putInt(KEY_NOTIFIED_VERSION, update.versionCode).apply()
    }

    /** Clears a stale notification once the phone runs that version or newer. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** DEFAULT, like the notices channel: worth a sound, not an interruption. */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_channel_description)
            },
        )
    }

    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_ID = "drawbridge-updates"
    private const val NOTIFICATION_ID = 3
    private const val REQUEST_CODE = 0x0E10
    private const val PREFS = "drawbridge_self_update"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
}
