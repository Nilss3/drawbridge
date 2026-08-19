package app.drawbridge.herald.ext

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the padding does when the bars go away, which is the half nobody could
 * see on an emulator.
 *
 * A hidden bar reports no inset, so every screen's chrome padding falls away by
 * itself in fullscreen. A display cutout does not: it is a hole in the panel
 * rather than a bar, its inset is reported whether anything is showing or not,
 * and `enterImmersiveMode` stretches the window underneath it on purpose. So a
 * phone with a camera hole kept a strip of toolbar colour across the top of a
 * fullscreen video, and a phone without one — every emulator this was checked
 * against — looked perfect.
 *
 * Both cases are here rather than only the broken one: dropping the cutout
 * unconditionally would put the toolbar back under the camera in landscape,
 * which is the bug the inset was added to fix in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InsetsExtTest {

    private val statusBar = 96
    private val navigationBar = 48
    private val cutout = 120

    private fun paddedView(): View =
        View(ApplicationProvider.getApplicationContext<Application>()).apply {
            applySystemBarInsets(top = true, bottom = true, sides = true)
        }

    /**
     * The bars carry their own insets and the cutout is always reported, exactly
     * as the platform hands them over: hiding a bar zeroes that bar's inset and
     * leaves the hole where it was.
     */
    private fun insets(barsVisible: Boolean): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setVisible(WindowInsetsCompat.Type.statusBars(), barsVisible)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), barsVisible)
            .setInsets(
                WindowInsetsCompat.Type.statusBars(),
                if (barsVisible) Insets.of(0, statusBar, 0, 0) else Insets.NONE,
            )
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                if (barsVisible) Insets.of(0, 0, 0, navigationBar) else Insets.NONE,
            )
            .setDisplayCutout(DisplayCutoutCompat(Rect(0, cutout, 0, 0), emptyList()))
            .build()

    @Test
    fun `pads past the camera hole while the bars are showing`() {
        val view = paddedView()

        ViewCompat.dispatchApplyWindowInsets(view, insets(barsVisible = true))

        // The cutout is the taller of the two, and the point of asking for both.
        assertEquals(cutout, view.paddingTop)
        assertEquals(navigationBar, view.paddingBottom)
    }

    /**
     * The bug, in one assertion. `paddingTop` was the cutout height here, and
     * that strip is drawn in the toolbar colour.
     */
    @Test
    fun `pads by nothing at all once the bars are hidden`() {
        val view = paddedView()

        ViewCompat.dispatchApplyWindowInsets(view, insets(barsVisible = false))

        assertEquals(0, view.paddingTop)
        assertEquals(0, view.paddingBottom)
    }

    /**
     * Fullscreen ends, the bars come back, and the padding has to come back with
     * them — the listener recomputes from the view's original padding rather
     * than from what it last set, so a round trip has to land where it started.
     */
    @Test
    fun `puts the padding back when the bars return`() {
        val view = paddedView()

        ViewCompat.dispatchApplyWindowInsets(view, insets(barsVisible = true))
        ViewCompat.dispatchApplyWindowInsets(view, insets(barsVisible = false))
        ViewCompat.dispatchApplyWindowInsets(view, insets(barsVisible = true))

        assertEquals(cutout, view.paddingTop)
        assertEquals(navigationBar, view.paddingBottom)
    }
}
