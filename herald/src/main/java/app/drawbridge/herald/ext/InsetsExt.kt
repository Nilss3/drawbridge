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
 * fullscreen video need no special case — see [insetTypes] for the one type that
 * does not.
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
        val bars = windowInsets.getInsets(insetTypes(windowInsets))
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

/**
 * What to pad by, which is not the same list in fullscreen as out of it.
 *
 * A hidden bar reports no inset, so hiding the bars is all it takes for the
 * padding to fall away — but **a display cutout is not a bar**. It is a hole in
 * the panel, it is still there when everything is hidden, and its inset is
 * reported the whole time. `enterImmersiveMode` makes that worse rather than
 * better: it sets `FLAG_LAYOUT_NO_LIMITS` and cutout mode `SHORT_EDGES`, so the
 * window is deliberately stretched underneath the hole. Padding for the cutout
 * there left a strip of toolbar colour across the camera on a phone that had
 * just been asked to show nothing but video.
 *
 * The cutout is in this list to keep the *toolbar* clear of the hole — in
 * landscape it sits beside the toolbar rather than in the status bar, so the
 * bar's own inset does not cover it. Once the system bars are hidden there is no
 * toolbar to keep clear: [app.drawbridge.herald.browser.BrowserFragment] hides
 * it in the same breath.
 *
 * Keyed on the status bar rather than on a fullscreen flag of our own, so it
 * follows the window the padding is being computed for. Nothing has to tell this
 * function what state the browser thinks it is in.
 *
 * Never caught before a phone ran it: the emulator this was written against has
 * no cutout, so the inset it hinges on was zero every time it was checked.
 */
private fun insetTypes(insets: WindowInsetsCompat): Int {
    val bars = WindowInsetsCompat.Type.systemBars()
    return if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
        bars or WindowInsetsCompat.Type.displayCutout()
    } else {
        bars
    }
}
