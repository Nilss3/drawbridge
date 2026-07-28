package app.drawbridge.herald.ext

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import app.drawbridge.herald.HeraldApplication
import app.drawbridge.herald.R
import app.drawbridge.herald.components.Components

val Context.components: Components
    get() = (applicationContext as HeraldApplication).components

val Fragment.requireComponents: Components
    get() = requireContext().components

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
