package app.drawbridge.dpc.apps.store

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.policy.model.AppRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cache, and specifically the two ways it can be quietly wrong.
 *
 * Reading is on the hot path — [StoreCatalogue.verdictFor] is called from the
 * install receiver and from a sweep over every package — so it answers from disk
 * and never from the network. That makes the *staleness* rules the whole
 * correctness story: a cache that hands back a year-old answer, or an answer
 * about a version the phone no longer has, is a rule enforcing something nobody
 * decided.
 *
 * Nothing here goes near [StoreCatalogue.fetch]; a test that reached
 * play.google.com would be a test that fails on a train.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class StoreCatalogueTest {

    private lateinit var catalogue: StoreCatalogue

    private val ratings = AppRatings(
        allowedRatings = listOf("pegi 3"),
        blockedCategoryPrefixes = listOf("GAME_"),
        blockedCategories = listOf("DATING"),
    )

    @Before
    fun setUp() {
        catalogue = StoreCatalogue(ApplicationProvider.getApplicationContext<android.app.Application>())
        catalogue.clear()
    }

    /**
     * The default state of every package on every phone, and it must be *keep*.
     * A first lock has not scanned anything yet, a sideloaded app is not on the
     * store at all, and neither is a reason to uninstall.
     */
    @Test
    fun `an unknown package is unverified, which means keep`() {
        assertEquals(
            AppRatings.Verdict.UNVERIFIED,
            catalogue.verdictFor("com.example.unknown", ratings),
        )
        assertFalse(catalogue.isFresh("com.example.unknown"))
    }

    @Test
    fun `clearing forgets everything`() {
        catalogue.clear()
        assertEquals(0, catalogue.stats().known)
        assertEquals(emptyList<String>(), catalogue.unverified())
    }

    /**
     * Fail-open is only a decision while it is countable. A phone that cannot
     * reach the store keeps every app on it and looks exactly like a phone where
     * the rule is working, so the count is the only thing that distinguishes
     * them — and it is what Diagnostics prints.
     */
    @Test
    fun `stats start empty and stay honest`() {
        val stats = catalogue.stats()
        assertEquals(0, stats.known)
        assertEquals(0, stats.usable)
        assertEquals(0, stats.failed)
        assertEquals(0L, stats.newestFetchMillis)
    }

    /**
     * The rule is the policy's, not the cache's. Two documents disagreeing about
     * the same stored answer is the whole reason thresholds live in a signed
     * file — a re-sign has to be able to change a verdict without a phone
     * re-fetching 80 pages.
     */
    @Test
    fun `the same cached answer is read through whichever policy is current`() {
        val strict = AppRatings(allowedRatings = listOf("pegi 3"))
        val lax = AppRatings(allowedRatings = listOf("pegi 3", "pegi 12"))

        assertEquals(AppRatings.Verdict.BLOCKED, strict.verdict("PEGI 12", "PRODUCTIVITY"))
        assertEquals(AppRatings.Verdict.ALLOWED, lax.verdict("PEGI 12", "PRODUCTIVITY"))
    }

    /**
     * A package the phone does not have reports version 0, and a cached entry
     * recorded against a real version therefore cannot match it. That is the
     * behaviour that makes an uninstall-then-reinstall re-ask the store, which
     * is right: the reinstalled thing may not be what was there before.
     */
    @Test
    fun `an entry for a package this phone does not have is never fresh`() {
        assertFalse(catalogue.isFresh("com.example.nothere"))
        assertEquals(
            AppRatings.Verdict.UNVERIFIED,
            catalogue.verdictFor("com.example.nothere", ratings),
        )
    }
}
