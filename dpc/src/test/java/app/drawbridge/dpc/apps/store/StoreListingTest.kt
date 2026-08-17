package app.drawbridge.dpc.apps.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against the page shape that has already fooled it twice.
 *
 * Both failures were the same: a Play listing carries two to four *different*
 * rating strings — the app's own, plus one per "similar apps" carousel entry —
 * so anything like a text search for `PEGI` returns a neighbour's rating.
 * Measured on 2026-08-16, 75 of 86 real pages carried more than one. The second
 * failure reported `com.scatterlab.messenger` as PEGI 3 when it is *Parental
 * guidance*, and it read as a finding rather than a bug for long enough to be
 * written into a document.
 *
 * So the fixture below is **not** a captured page. It is a synthetic one built
 * to contain exactly those traps, in the order a real page carries them: the
 * decoys come *before* the anchored fields, which is what makes a first-match
 * regex pick the wrong one. A real 1.2 MB capture would be a worse test and a
 * worse thing to keep in git — it would assert that today's page shape has not
 * changed, rather than that this parser reads the right field.
 */
class StoreListingTest {

    /** Carousel entries first, exactly as the real page orders them. */
    private fun page(
        rating: String = "PEGI 3",
        category: String = "PRODUCTIVITY",
        microdata: Boolean = true,
        jsonLd: Boolean = true,
    ): String = buildString {
        append("""<html><head><title>An App - Apps on Google Play</title></head><body>""")
        // The trap: three neighbouring apps, each with its own rating, in the
        // JS blob that drives the "similar apps" row.
        append("""<script>AF_initDataCallback({data:[[["Some Other App"],null,["PEGI 18",""")
        append("""[null,2,[512,512]]]],[["A Third App"],null,["PEGI 16",[null,2,[512,512]]]],""")
        append("""[["And Another"],null,["Parental guidance",[null,2,[512,512]]]]]});</script>""")
        if (microdata) {
            append("""<div><img alt="Content rating" />""")
            append("""<span itemprop="contentRating"><span>$rating</span></span></div>""")
        }
        if (jsonLd) {
            append("""<script type="application/ld+json">{"@type":"SoftwareApplication",""")
            append(""""contentRating":"$rating","applicationCategory":"$category"}</script>""")
        }
        append("</body></html>")
    }

    // --- the trap ------------------------------------------------------------

    @Test
    fun `reads the app's own rating, not the carousel's`() {
        val listing = StoreListing.parse("com.example.app", page(rating = "PEGI 3"))

        assertEquals(
            "the page carries PEGI 18, PEGI 16 and Parental guidance for *other* apps, " +
                "all of them before the real one",
            "PEGI 3",
            listing.rating,
        )
        assertTrue(listing.isUsable)
    }

    @Test
    fun `a text search for the rating would fail this fixture`() {
        // Stated as a test so the trap is executable rather than a comment. If
        // somebody ever "simplifies" the parser to a plain search, this is the
        // assertion that explains what they broke.
        val html = page(rating = "PEGI 3")
        val naive = Regex("""(PEGI \d+|Parental guidance)""").find(html)?.value

        assertEquals("a naive search finds a neighbour's rating first", "PEGI 18", naive)
        assertEquals("the parser does not", "PEGI 3", StoreListing.parse("x", html).rating)
    }

    // --- the two anchors -----------------------------------------------------

    @Test
    fun `either anchor alone is enough`() {
        assertEquals("PEGI 12", StoreListing.parse("x", page("PEGI 12", jsonLd = false)).rating)
        assertEquals("PEGI 12", StoreListing.parse("x", page("PEGI 12", microdata = false)).rating)
    }

    /**
     * They agreed on all 86 pages measured, so a disagreement means the page
     * shape has moved and this parser is no longer reading what it believes it
     * is. Picking one would be picking at random, and the wrong pick removes
     * somebody's app.
     */
    @Test
    fun `disagreeing anchors are a failure, not a tie to break`() {
        val html = page("PEGI 3").replace(""""contentRating":"PEGI 3"""", """"contentRating":"PEGI 18"""")
        val listing = StoreListing.parse("com.example.app", html)

        assertNull("no rating is reported", listing.rating)
        assertFalse(listing.isUsable)
        assertNotNull(listing.error)
        assertTrue(
            "the error has to name both values or it cannot be diagnosed: ${listing.error}",
            listing.error!!.contains("PEGI 3") && listing.error.contains("PEGI 18"),
        )
    }

    // --- what it does with a page it cannot use ------------------------------

    @Test
    fun `a page with no rating block is a failure rather than an empty answer`() {
        val listing = StoreListing.parse("x", page(microdata = false, jsonLd = false))

        assertNull(listing.rating)
        assertNotNull("an absent rating must be reportable, not silently permissive", listing.error)
    }

    @Test
    fun `garbage does not throw`() {
        // Reached by an error page served with a 200, or a captive portal. The
        // caller has no branch for an exception here; it has one for a failure.
        for (input in listOf("", "<html></html>", "not html at all", "{[<>]}")) {
            val listing = StoreListing.parse("x", input)
            assertNotNull("input '${input.take(12)}' should be a failure", listing.error)
        }
    }

    @Test
    fun `the category is read when present and tolerated when absent`() {
        assertEquals("DATING", StoreListing.parse("x", page(category = "DATING")).category)

        val noCategory = page().replace(""","applicationCategory":"PRODUCTIVITY"""", "")
        val listing = StoreListing.parse("x", noCategory)
        assertNull(listing.category)
        assertTrue("a missing category must not invalidate a good rating", listing.isUsable)
    }

    // --- the URL -------------------------------------------------------------

    /**
     * Both halves are load-bearing. `hl=en` keeps the rating *id* stable, since
     * the title is localised and a policy table cannot be written against three
     * translations of it. `gl` is the policy's, because the rating itself is
     * regional: `com.scatterlab.messenger` is *Mature 17+* in the US and
     * *Parental guidance* in Belgium.
     */
    @Test
    fun `the url pins the language and takes the region from policy`() {
        val url = StoreListing.url("com.example.app", "BE")

        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("id=com.example.app"))
        assertTrue("the language is pinned, not the device's", url.contains("hl=en"))
        assertTrue(url.contains("gl=BE"))
        assertEquals("a different region is a different question", true,
            StoreListing.url("com.example.app", "US").contains("gl=US"))
    }
}
