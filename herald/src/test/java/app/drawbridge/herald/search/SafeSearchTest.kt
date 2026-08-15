package app.drawbridge.herald.search

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM, no Robolectric: the rewrite works on the raw query string on
 * purpose, partly so this file can exist without dragging an Android test
 * runtime into a module that has none.
 */
class SafeSearchTest {

    @Test
    fun `adds the parameter each engine wants`() {
        assertTrue(
            SafeSearch.enforced("https://www.google.com/search?q=cats")!!
                .contains("safe=active"),
        )
        assertTrue(
            SafeSearch.enforced("https://www.bing.com/search?q=cats")!!
                .contains("adlt=strict"),
        )
        assertTrue(
            SafeSearch.enforced("https://duckduckgo.com/?q=cats")!!.contains("kp=1"),
        )
    }

    /**
     * **Ecosia is not rewritten any more, and that is the point rather than an
     * omission** — 2026-08-15.
     *
     * Its `safesearch=2` was honoured and this file did put it back, so inside
     * herald the engine really was forced. It was dropped because that is all it
     * ever was: the policy allows Chrome, Firefox Focus and Vivaldi as well, and
     * in any of those ecosia.org was unfiltered search that nothing could
     * rewrite. The engine is out of `browser.search_engines`, the app is out of
     * `allowed_browser_packages`, and `ecosia.org` is on the search blocklist as
     * of policy 55.
     *
     * Asserted rather than merely deleted because the deletion is the behaviour
     * change, and a rule quietly coming back would look like a fix.
     */
    @Test
    fun `no longer touches Ecosia, which is blocked rather than forced`() {
        assertNull(SafeSearch.enforced("https://www.ecosia.org/search?q=cats"))
        assertNull(SafeSearch.enforced("https://www.ecosia.org/search?q=cats&safesearch=0"))
    }

    /**
     * The loop guard. The rewritten URL comes straight back through the
     * interceptor, so a URL that already carries the parameter has to be
     * recognised as finished or the browser redirects forever.
     */
    @Test
    fun `leaves a url that already carries the parameter alone`() {
        assertNull(SafeSearch.enforced("https://www.google.com/search?q=cats&safe=active"))
        assertNull(SafeSearch.enforced("https://www.bing.com/search?q=cats&adlt=strict"))
        assertNull(SafeSearch.enforced(SafeSearch.enforced("https://duckduckgo.com/?q=cats")!!))
    }

    /** The address-bar case this exists for: the parameter removed by hand. */
    @Test
    fun `overrides a parameter that has been turned off`() {
        val fixed = SafeSearch.enforced("https://www.bing.com/search?q=cats&adlt=off")!!
        assertTrue(fixed.contains("adlt=strict"))
        assertTrue("the disabled value must not survive", !fixed.contains("adlt=off"))
    }

    @Test
    fun `keeps the query and any other parameters`() {
        val fixed = SafeSearch.enforced("https://www.google.com/search?q=two+words&hl=nl")!!
        assertTrue(fixed.contains("q=two"))
        assertTrue(fixed.contains("hl=nl"))
    }

    @Test
    fun `ignores anything that is not a search`() {
        assertNull(SafeSearch.enforced("https://www.google.com/"))
        assertNull(SafeSearch.enforced("https://www.bing.com/"))
        assertNull(SafeSearch.enforced("https://en.wikipedia.org/wiki/Cat?q=cats"))
        assertNull(SafeSearch.enforced("not a url at all"))
    }

    /**
     * Kagi is offered and deliberately has no rule: logged out it filters and
     * offers no setting to stop. A rule here would be a parameter it ignores,
     * which is exactly the kind of reassuring no-op the removed engines were
     * dropped for.
     */
    @Test
    fun `leaves Kagi alone`() {
        assertNull(SafeSearch.enforced("https://kagi.com/search?q=cats"))
    }

    /**
     * Subdomains count. Google in particular is reached through dozens of
     * country hosts, and `www.google.de` filters exactly as `www.google.com`
     * does.
     */
    @Test
    fun `matches subdomains of a known engine`() {
        assertTrue(
            SafeSearch.enforced("https://images.google.com/search?q=cats")!!
                .contains("safe=active"),
        )
    }

    /**
     * Google's country front ends. google.be is not a subdomain of google.com,
     * so a suffix match would have left every one of them unforced — the same
     * gap this file exists to close, reintroduced one domain over.
     */
    @Test
    fun `forces Google country domains too`() {
        listOf(
            "https://www.google.be/search?q=cats",
            "https://google.nl/search?q=cats",
            "https://www.google.co.uk/search?q=cats",
        ).forEach { url ->
            assertTrue(url, SafeSearch.enforced(url)!!.contains("safe=active"))
        }
    }

    /** Not search front ends, and not ours to decorate. */
    @Test
    fun `leaves other Google hosts alone`() {
        assertNull(SafeSearch.enforced("https://fonts.googleapis.com/css?q=x"))
        assertNull(SafeSearch.enforced("https://lh3.googleusercontent.com/a?q=x"))
    }

    /** A search term is arbitrary text, and it has to survive untouched. */
    @Test
    fun `does not re-encode the query`() {
        val fixed = SafeSearch.enforced("https://www.bing.com/search?q=caf%C3%A9+d%27hiver")!!
        assertTrue(fixed.contains("q=caf%C3%A9+d%27hiver"))
    }

    @Test
    fun `keeps the fragment at the end`() {
        val fixed = SafeSearch.enforced("https://www.bing.com/search?q=cats#images")!!
        assertTrue(fixed.endsWith("#images"))
        assertTrue(fixed.contains("adlt=strict"))
    }
}
