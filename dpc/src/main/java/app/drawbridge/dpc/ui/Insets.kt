package app.drawbridge.dpc.ui

import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Insets a screen's content by the system bars and by the action bar above it.
 *
 * From API 35 the platform lays every app out edge to edge. AppCompat still
 * places the action bar below the status bar, but the content view now starts at
 * the top of the window, so without this the first lines of every screen are
 * hidden behind both.
 *
 * The action bar is only added when there is a non-zero top inset to correct:
 * below API 35 the decor view consumes the insets and lays the content out
 * itself, and this reports zero and changes nothing.
 */
fun View.applyScreenInsets() {
    val original = paddingTop to paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val actionBar = if (bars.top > 0) view.actionBarHeight() else 0

        view.setPadding(
            view.paddingLeft,
            original.first + bars.top + actionBar,
            view.paddingRight,
            original.second + bars.bottom,
        )
        windowInsets
    }

    ViewCompat.requestApplyInsets(this)
}

private fun View.actionBarHeight(): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)) {
        TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
    } else {
        0
    }
}
