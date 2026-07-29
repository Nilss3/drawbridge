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
    val loadDelayMillis: Long get() = if (isMono) 2_000L else 0L
}
