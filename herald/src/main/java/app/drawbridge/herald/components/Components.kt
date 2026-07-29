package app.drawbridge.herald.components

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.drawbridge.herald.filter.BlocklistExtension
import app.drawbridge.herald.filter.ContentBlockerExtension
import mozilla.components.support.base.android.NotificationsDelegate

/** Every long-lived component in the app, created lazily on first use. */
class Components(private val context: Context) {

    val downloads by lazy { Downloads(context.applicationContext) }

    val core by lazy { Core(context, downloads) }

    val useCases by lazy { UseCases(core.engine, core.store, downloads) }

    val filter by lazy { BlocklistExtension(context) }

    val contentBlocker by lazy { ContentBlockerExtension() }

    val notificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(context))
    }
}
