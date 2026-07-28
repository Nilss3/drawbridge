package app.drawbridge.herald.media

import app.drawbridge.herald.ext.components
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.base.android.NotificationsDelegate

/** Keeps audio and video playing while herald is in the background. */
class MediaSessionService : AbstractMediaSessionService() {
    override val store: BrowserStore by lazy { components.core.store }

    /** herald has no crash reporting: nothing about this device leaves it. */
    override val crashReporter: CrashReporting? = null

    override val notificationsDelegate: NotificationsDelegate by lazy {
        components.notificationsDelegate
    }
}
