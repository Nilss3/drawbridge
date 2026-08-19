package app.drawbridge.herald

/**
 * What separates herald from herald mono.
 *
 * The two editions are one source tree and one Gradle module; mono is the same
 * browser with the same filtering, bookmarks, history and ad blocking, and
 * differs only in what this object describes. Keeping the differences named here
 * rather than scattering `BuildConfig.MONO` through the code means the whole of
 * "what mono is" can be read in one place — and each switch says *why* it exists
 * rather than merely which build it belongs to.
 *
 * The flag is a compile-time constant, so R8 removes the branch that does not
 * apply from each edition's release build.
 */
object Edition {

    /** True in herald mono. */
    val isMono: Boolean get() = BuildConfig.MONO

    /**
     * Tabs exist at all: the tab counter, the tray, "New tab", and the
     * context-menu entries that open one.
     *
     * Mono has none, because one page at a time is the entire point of it.
     */
    val hasTabs: Boolean get() = !isMono

    /**
     * Pages render without colour.
     *
     * Applied to the engine's own surface rather than to the page, so it reaches
     * video, canvas and images alike and costs the page no layout. See
     * `browser.GreyscaleIntegration`.
     */
    val greyscale: Boolean get() = isMono

    /**
     * How long the browser makes you wait, deliberately, before showing a page
     * it has been asked to load.
     *
     * A pause you feel rather than a network delay: the page loads underneath
     * while the screen holds. The friction is the feature.
     */
    val loadDelayMillis: Long get() = if (isMono) 2_500L else 0L

    /**
     * A flick throws the page a shorter way.
     *
     * The same thesis as [loadDelayMillis]: friction rather than stripping. A
     * fling is the scroll of a reflex — the flick that carries a feed past
     * several screens without a decision being made in between — and slowing it
     * costs a reader who is reading nothing, because dragging still tracks the
     * finger exactly. See `components.EngineProvider.applySlowScrollingPrefs`
     * for the one Gecko setting this is, and for the one it is not.
     *
     * It replaced always-on reader view, which tried to reach the same end by
     * taking the page apart and depended on a Readability pass that either
     * worked or left the reader worse off. Reader view is still in the menu,
     * for the pages someone wants it on.
     */
    val slowScrolling: Boolean get() = isMono
}
