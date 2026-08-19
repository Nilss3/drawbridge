package app.drawbridge.policy.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The admission rule, as a table.
 *
 * Every threshold here has been moved at least once on measurement, and each
 * move was safe only because the rule is a pure function rather than a code path
 * through a network call. The numbers behind each case are in
 * docs/app-ratings.md; what this file asserts is that the rule expresses them.
 */
class AppRatingsTest {

    private val ratings = AppRatings(
        storeRegion = "BE",
        allowedRatings = listOf("pegi 3", "everyone", "usk: all ages", "rated 3+"),
        blockedCategoryPrefixes = listOf("GAME_"),
        blockedCategories = listOf("DATING"),
        allowedPackages = listOf("ch.threema.app", "com.strava"),
    )

    // --- ratings -------------------------------------------------------------

    @Test
    fun `PEGI 3 passes and everything above it does not`() {
        assertEquals(AppRatings.Verdict.ALLOWED, ratings.verdict("PEGI 3", "PRODUCTIVITY"))
        for (blocked in listOf("PEGI 7", "PEGI 12", "PEGI 16", "PEGI 18")) {
            assertEquals(blocked, AppRatings.Verdict.BLOCKED, ratings.verdict(blocked, "PRODUCTIVITY"))
        }
    }

    /**
     * The expensive half, and the one a future session is most likely to
     * question. Measured 2026-08-17: allowing this band would let through 42 of
     * 103 uncurated harm-adjacent apps, including TikTok Lite, every
     * anonymous-confession app found and every stranger-livestream app found.
     * It costs a wider `allowed_packages`, which is a list that can be written
     * down — unlike next month's confession apps.
     */
    @Test
    fun `parental guidance is blocked, and that is the whole argument`() {
        assertEquals(
            AppRatings.Verdict.BLOCKED,
            ratings.verdict("Parental guidance", "SOCIAL"),
        )
    }

    @Test
    fun `rating ids are compared case and whitespace insensitively`() {
        // The store returns a display string; policy stores an id. They differ
        // in case on every single lookup.
        for (spelling in listOf("PEGI 3", "pegi 3", "  PEGI 3  ", "Pegi 3")) {
            assertEquals(spelling, AppRatings.Verdict.ALLOWED, ratings.verdict(spelling, null))
        }
    }

    @Test
    fun `a missing rating is unverified rather than allowed`() {
        // An absent field is not a lenient one: the store answered without the
        // thing this rule turns on, so there is no verdict to give. The caller
        // keeps it *and counts it*, which is the difference between a fallback
        // and a hole.
        assertEquals(AppRatings.Verdict.UNVERIFIED, ratings.verdict(null, "PRODUCTIVITY"))
        assertEquals(AppRatings.Verdict.UNVERIFIED, ratings.verdict("", "PRODUCTIVITY"))
        assertEquals(AppRatings.Verdict.UNVERIFIED, ratings.verdict("   ", null))
    }

    // --- categories ----------------------------------------------------------

    /**
     * **The order is the finding.** Rating and category disagree on exactly the
     * case the category rule exists for: `AIKO: AI Girlfriend 3D Game` is PEGI 3
     * and `GAME_SIMULATION`, Candy Crush is PEGI 3 and `GAME_CASUAL`. Asking the
     * rating first lets both through, which is a phone with Candy Crush on it
     * and not Minecraft.
     */
    @Test
    fun `category beats an acceptable rating`() {
        assertEquals(
            "Candy Crush is PEGI 3 and a game; the game half has to win",
            AppRatings.Verdict.BLOCKED,
            ratings.verdict("PEGI 3", "GAME_CASUAL"),
        )
        assertEquals(
            AppRatings.Verdict.BLOCKED,
            ratings.verdict("PEGI 3", "DATING"),
        )
    }

    @Test
    fun `the game rule is a prefix, so a new Google category is covered`() {
        for (category in listOf(
            "GAME_CASUAL", "GAME_ARCADE", "GAME_STRATEGY", "GAME_ADVENTURE",
            "GAME_SIMULATION", "GAME_EDUCATIONAL", "GAME_SOMETHING_NEW",
        )) {
            assertEquals(category, AppRatings.Verdict.BLOCKED, ratings.verdict("PEGI 3", category))
        }
    }

    @Test
    fun `a category that merely contains the word game is not a game`() {
        // Prefix, not substring: an app filed under something like BOARD_GAMES
        // by a future taxonomy is a different claim from GAME_BOARD.
        assertEquals(AppRatings.Verdict.ALLOWED, ratings.verdict("PEGI 3", "BOARD_GAMES_NEWS"))
    }

    @Test
    fun `an absent category does not stop a good rating`() {
        assertEquals(AppRatings.Verdict.ALLOWED, ratings.verdict("PEGI 3", null))
        assertEquals(AppRatings.Verdict.ALLOWED, ratings.verdict("PEGI 3", ""))
    }

    // --- the whitelist and the off switch ------------------------------------

    @Test
    fun `the whitelist names packages kept whatever the store says`() {
        assertTrue(ratings.isAlwaysAllowed("ch.threema.app"))
        assertFalse(ratings.isAlwaysAllowed("com.example.other"))
    }

    /**
     * An empty `allowed_ratings` cannot express "these pass", and reading it as
     * "nothing passes" would remove every app on the phone. Same shape as the
     * install lock's absent-versus-empty snapshot: a rule that removes what is
     * *not* named has to refuse to run rather than guess.
     */
    @Test
    fun `an unconfigured policy has no opinion`() {
        assertFalse(AppRatings().isConfigured)
        assertTrue(ratings.isConfigured)
    }

    // --- the wire format -----------------------------------------------------

    /**
     * A mismatched `@SerialName` leaves every field at its default on every
     * phone, and the rule then silently does nothing — the same failure shape as
     * the Shorts rewrite that shipped twice without running, and the reason
     * `PolicyOptionTest` asserts the same thing for `various_ages`.
     */
    @Test
    fun `it parses under the document's own spelling`() {
        val parsed = JSON.decodeFromString<AppRatings>(
            """
            {
              "store_region": "BE",
              "allowed_ratings": ["pegi 3"],
              "blocked_category_prefixes": ["GAME_"],
              "blocked_categories": ["DATING"],
              "allowed_packages": ["ch.threema.app"]
            }
            """.trimIndent(),
        )

        assertEquals("BE", parsed.storeRegion)
        assertEquals(listOf("pegi 3"), parsed.allowedRatings)
        assertEquals(listOf("GAME_"), parsed.blockedCategoryPrefixes)
        assertEquals(listOf("DATING"), parsed.blockedCategories)
        assertEquals(listOf("ch.threema.app"), parsed.allowedPackages)
        assertTrue(parsed.isConfigured)
    }

    @Test
    fun `a document without the block leaves the rule off`() {
        val policy = JSON.decodeFromString<Policy>("""{"version": 1}""")

        // Every phone in the field predates this field. Defaulting it to an
        // active rule would have them start removing apps on the next poll.
        assertEquals(null, policy.appRatings)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
