package app.drawbridge.dpc.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Insets a screen's content by whatever the window is not free to draw under.
 *
 * From API 35 the platform lays every app out edge to edge. The content view now
 * starts at the top of the window, so without this the first lines of every
 * screen would sit behind the status bar and the action bar both.
 *
 * **The action bar is already in the number this reads, and adding it again was
 * a 64dp band of nothing under the title bar** — reported from build 41 on
 * 2026-08-19, measured on the API 36 emulator on 2026-08-24. AppCompat's
 * `ActionBarOverlayLayout` folds the bar it draws into the insets it hands down
 * to the content: `systemBars()` arrives here as 231px on a 420dpi phone, which
 * is the 24dp status bar plus the 64dp action bar, not the 24dp it looks like.
 * The old code took that for the status bar alone and added `actionBarSize` on
 * top of it, so every screen began 64dp lower than it had any reason to.
 *
 * Below API 35 the decor view consumes the insets and lays the content out
 * itself; this reports zero and changes nothing, which is why the bug was
 * invisible until the first phone running 35 or later.
 */
fun View.applyScreenInsets() {
    val original = paddingTop to paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )

        view.setPadding(
            view.paddingLeft,
            original.first + bars.top,
            view.paddingRight,
            original.second + bars.bottom,
        )
        windowInsets
    }

    ViewCompat.requestApplyInsets(this)
}
