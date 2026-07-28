package app.drawbridge.herald.ext

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Insets this view by the system bars, on top of whatever padding it already has.
 *
 * From API 35 the platform lays every app out edge to edge: the status and
 * navigation bars are drawn over the window, `android:statusBarColor` is ignored,
 * and a view at the top of the layout ends up underneath the status bar unless it
 * asks for the inset itself. Below API 35 the decor view still consumes these
 * insets, so this reports zero and changes nothing.
 *
 * Padding rather than margin, so the strip the bar sits over keeps the view's own
 * background. The insets go to zero while the bars are hidden, which is what makes
 * fullscreen video need no special case.
 */
fun View.applySystemBarInsets(
    top: Boolean = false,
    bottom: Boolean = false,
    sides: Boolean = false,
) {
    // The listener fires again on every rotation and bar change, so the padding
    // has to be recomputed from the original values rather than added to itself.
    val original = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(
            original.left + if (sides) bars.left else 0,
            original.top + if (top) bars.top else 0,
            original.right + if (sides) bars.right else 0,
            original.bottom + if (bottom) bars.bottom else 0,
        )
        windowInsets
    }

    ViewCompat.requestApplyInsets(this)
}
