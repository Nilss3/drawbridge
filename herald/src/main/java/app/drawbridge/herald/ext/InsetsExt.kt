package app.drawbridge.herald.ext

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Insets this view by the system bars and by the on-screen keyboard, on top of
 * whatever padding it already has.
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
 *
 * The keyboard rides on [bottom], because that is the edge it comes up from and
 * because a caller that does not want its bottom edge moved does not want it
 * moved by an input method either. See [keyboardInsetType] for why the keyboard
 * is in this list at all.
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
 *
 * The keyboard is asked for unconditionally. It reports nothing while it is
 * down, and a window that has hidden its bars for a video is not one an input
 * method is about to open over — but if it does, the padding is still the right
 * answer.
 */
private fun insetTypes(insets: WindowInsetsCompat): Int {
    val bars = WindowInsetsCompat.Type.systemBars() or keyboardInsetType(Build.VERSION.SDK_INT)
    return if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
        bars or WindowInsetsCompat.Type.displayCutout()
    } else {
        bars
    }
}

/**
 * The keyboard, on the versions where asking for it is not asking twice.
 *
 * **`adjustResize` in the manifest stopped being enough at API 35.** It resizes
 * the window only while the decor view is fitting the system windows, and from
 * API 35 an app is laid out edge to edge with no way back — so the input method
 * arrives as an inset nobody applies, and it is simply drawn over the bottom of
 * the window. A form field near the foot of a page ends up behind the keys with
 * the page underneath none the wiser: no reflow, no scroll, because as far as
 * Gecko is concerned the viewport never changed. Padding the browser's root by
 * the keyboard's height shortens the engine view instead, and Gecko scrolls the
 * focused field back into what is left, which is the shift up that every other
 * browser does.
 *
 * `systemBars() or ime()` is deliberate rather than an addition: a combined mask
 * returns the larger of the two per edge, and the keyboard is drawn over the
 * navigation bar rather than above it. Adding them would leave a navigation
 * bar's worth of empty chrome between the page and the keys.
 *
 * **Below API 30 the keyboard's inset is a guess, and it is a guess at a number
 * the platform has already applied.** `WindowInsetsCompat` cannot ask for
 * `Type.ime()` there — no such thing existed until Android 11 — so it infers one
 * by reflecting into `ViewRootImpl` for the visible insets and taking whatever
 * exceeds the stable inset. Those versions do not go edge to edge, `adjustResize`
 * has already inset the content by exactly that much, and padding by it again
 * would push the page up twice. So this asks for nothing on 28 and 29, where the
 * platform still does the work itself.
 */
internal fun keyboardInsetType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) WindowInsetsCompat.Type.ime() else 0
