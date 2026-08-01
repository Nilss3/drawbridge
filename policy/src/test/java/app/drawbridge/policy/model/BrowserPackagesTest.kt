package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set the app blocker checks against.
 *
 * Worth its own test because getting it wrong is not a cosmetic bug: a browser
 * that `required_apps` installs and the blocker does not recognise gets removed
 * seconds later and reinstalled at the next poll, forever.
 */
class BrowserPackagesTest {

    @Test
    fun `a policy written before the list existed still names one browser`() {
        val old = Policy(version = 1, allowedBrowserPackage = "app.drawbridge.herald")

        assertEquals(setOf("app.drawbridge.herald"), old.browserPackages)
    }

    @Test
    fun `both editions are allowed when both are named`() {
        val both = Policy(
            version = 17,
            allowedBrowserPackage = "app.drawbridge.herald",
            allowedBrowserPackages = listOf(
                "app.drawbridge.herald",
                "app.drawbridge.heraldmono",
            ),
        )

        assertEquals(
            setOf("app.drawbridge.herald", "app.drawbridge.heraldmono"),
            both.browserPackages,
        )
    }

    @Test
    fun `the default link handler is always a member, even if the list forgets it`() {
        // The likeliest way to write this field wrong, and it would uninstall the
        // browser tapped links are handed to.
        val inconsistent = Policy(
            version = 17,
            allowedBrowserPackage = "app.drawbridge.herald",
            allowedBrowserPackages = listOf("app.drawbridge.heraldmono"),
        )

        assertTrue("app.drawbridge.herald" in inconsistent.browserPackages)
        assertEquals(2, inconsistent.browserPackages.size)
    }
}
