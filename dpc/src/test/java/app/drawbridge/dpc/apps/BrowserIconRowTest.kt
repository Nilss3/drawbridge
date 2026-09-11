package app.drawbridge.dpc.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a browser card's icon row claims.
 *
 * The row is a picture of the policy, so the two ways to get it wrong are both
 * lies about the policy: drawing a browser that is not allowed, and a **+** that
 * appears when nothing is behind it.
 */
class BrowserIconRowTest {

    private val everyAllowed = setOf(
        "app.drawbridge.herald",
        BrowserSettings.MONO_PACKAGE,
        "com.android.chrome",
        "org.mozilla.focus",
        "com.vivaldi.browser",
        "ai.perplexity.comet",
        "mark.via.gp",
        "app.vanadium.browser",
    )

    @Test
    fun `allow the browsers draws the four with pictures, then a plus`() {
        val row = BrowserSettings.iconRow(everyAllowed)
        assertEquals(
            listOf("app.drawbridge.herald", "com.android.chrome", "org.mozilla.focus", "com.vivaldi.browser"),
            row.shown,
        )
        assertTrue(row.more)
    }

    /** herald mono is not featured, and its card must still draw it rather than nothing. */
    @Test
    fun `herald mono only draws herald mono and no plus`() {
        val row = BrowserSettings.iconRow(setOf(BrowserSettings.MONO_PACKAGE))
        assertEquals(listOf(BrowserSettings.MONO_PACKAGE), row.shown)
        assertFalse(row.more)
    }

    @Test
    fun `no browser draws nothing and no plus`() {
        val row = BrowserSettings.iconRow(emptySet())
        assertEquals(emptyList<String>(), row.shown)
        assertFalse(row.more)
    }

    /** A document that stops sanctioning Chrome must not have it drawn anyway. */
    @Test
    fun `a featured browser the policy no longer allows is not drawn`() {
        val row = BrowserSettings.iconRow(everyAllowed - "com.android.chrome")
        assertEquals(
            listOf("app.drawbridge.herald", "org.mozilla.focus", "com.vivaldi.browser", "ai.perplexity.comet"),
            row.shown,
        )
        assertTrue(row.more)
    }

    /** The plus means "there are more", so exactly four allowed draws four and no plus. */
    @Test
    fun `exactly the featured four draws no plus`() {
        val row = BrowserSettings.iconRow(BrowserSettings.FEATURED_BROWSERS.toSet())
        assertEquals(BrowserSettings.FEATURED_BROWSERS, row.shown)
        assertFalse(row.more)
    }

    @Test
    fun `every allowed browser is drawn or behind the plus, and nothing else is drawn`() {
        for (allowed in listOf(everyAllowed, setOf("app.drawbridge.herald"), everyAllowed - "app.drawbridge.herald")) {
            val row = BrowserSettings.iconRow(allowed)
            assertTrue(allowed.containsAll(row.shown))
            assertEquals(row.shown.size < allowed.size, row.more)
        }
    }
}
