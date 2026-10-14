package app.drawbridge.herald.components

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.drawbridge.herald.filter.BlocklistExtension
import app.drawbridge.herald.filter.ContentBlockerExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.support.base.android.NotificationsDelegate

/** Every long-lived component in the app, created lazily on first use. */
class Components(private val context: Context) {

    /**
     * Work that outlives any one screen. Android Components asks for this by
     * name from 154 on — session storage, saved logins and the upload cleaner
     * each used to make their own — and lives as long as the process.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val downloads by lazy { Downloads(context.applicationContext) }

    val core by lazy { Core(context, downloads, applicationScope) }

    val useCases by lazy { UseCases(core.engine, core.store, downloads) }

    val filter by lazy { BlocklistExtension(context) }

    val contentBlocker by lazy { ContentBlockerExtension() }

    val notificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(context))
    }
}
