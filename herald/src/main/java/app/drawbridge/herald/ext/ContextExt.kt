package app.drawbridge.herald.ext

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.fragment.app.Fragment
import app.drawbridge.herald.HeraldApplication
import app.drawbridge.herald.R
import app.drawbridge.herald.components.Components
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme

val Context.components: Components
    get() = (applicationContext as HeraldApplication).components

val Fragment.requireComponents: Components
    get() = requireContext().components

/**
 * The scheme Gecko hands to `prefers-color-scheme`, taken from the phone's
 * day/night setting.
 *
 * Read from the configuration rather than left as [PreferredColorScheme.System]:
 * the runtime resolves "system" once and keeps it, so a phone that switches to
 * dark while herald is running would go on rendering pages — and the block page —
 * light until the process restarted.
 */
val Context.preferredColorScheme: PreferredColorScheme
    get() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            PreferredColorScheme.Dark
        } else {
            PreferredColorScheme.Light
        }
    }

fun Context.share(text: String, subject: String = "") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(Intent.createChooser(intent, getString(R.string.share_via)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}
