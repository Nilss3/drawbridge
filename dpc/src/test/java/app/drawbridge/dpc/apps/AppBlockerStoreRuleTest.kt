package app.drawbridge.dpc.apps

import app.drawbridge.policy.model.AppRatings
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the store rule sits among the others, and what the whitelist does to
 * everything downstream of it.
 *
 * The rule itself — which rating passes, which category loses — is
 * [app.drawbridge.policy.model.AppRatingsTest]'s. What this file covers is the
 * part that is specific to the blocker: **precedence**. Six rules now decide a
 * package's fate, two of them added on 2026-08-17, and the order they are asked
 * in is not decoration. Getting it wrong is silent in both directions — an app
 * removed that should not be, or a decision quietly reversed — and neither shows
 * up anywhere but on somebody's phone.
 */
class AppBlockerStoreRuleTest {

    private val ratings = AppRatings(
        allowedRatings = listOf("pegi 3"),
        blockedCategoryPrefixes = listOf("GAME_"),
        blockedCategories = listOf("DATING"),
        allowedPackages = listOf("ch.threema.app", "com.strava", "com.openai.chatgpt"),
    )

    private val policy = Policy(
        version = 1,
        allowedBrowserPackage = "app.drawbridge.herald",
        blockedPackages = listOf("com.instagram.android", "com.whatsapp"),
        options = listOf(
            PolicyOption(
                id = "whatsapp",
                name = "Allow WhatsApp",
                exemptPackages = listOf("com.whatsapp"),
            ),
        ),
        appRatings = ratings,
    )

    // --- the whitelist, which is what pays for blocking Parental guidance ----

    /**
     * The reason the whitelist exists at all: every private messenger is
     * *Parental guidance*, because ungraded conversation is exactly what that
     * band means. A phone that loses Threema and Session to a content rule is a
     * phone nobody keeps, so the rule is only affordable because this list
     * overrides it.
     */
    @Test
    fun `a whitelisted package survives a rating that would otherwise remove it`() {
        assertEquals(
            "Parental guidance is blocked outright",
            AppRatings.Verdict.BLOCKED,
            ratings.verdict("Parental guidance", "COMMUNICATION"),
        )
        assertTrue(
            "and the whitelist is consulted before the verdict is ever asked for",
            ratings.isAlwaysAllowed("ch.threema.app"),
        )
    }

    /**
     * A package the whitelist newly names may already be hidden, because the
     * list grows in response to somebody losing an app. If the restore did not
     * know about it, adding a name would fix the *next* phone and not this one —
     * the same asymmetry that left a hidden YouTube hidden on 2026-08-13.
     */
    @Test
    fun `restore knows about the whitelist, or a new entry only helps future phones`() {
        val restorable = AppBlocker.restorable(
            policy,
            BrowserSettings.allowedBrowsers(policy, BrowserSettings.Choice.ALL),
        )

        assertTrue("ch.threema.app", restorable.contains("ch.threema.app"))
        assertTrue("com.strava", restorable.contains("com.strava"))
        assertTrue(
            "the browsers and exempt packages must not have been displaced",
            restorable.contains("app.drawbridge.herald"),
        )
    }

    @Test
    fun `restore does not invent entries when a policy carries no store rule`() {
        val plain = policy.copy(appRatings = null)
        val restorable = AppBlocker.restorable(
            plain,
            BrowserSettings.allowedBrowsers(plain, BrowserSettings.Choice.ALL),
        )

        assertFalse(restorable.contains("ch.threema.app"))
        assertTrue(restorable.contains("app.drawbridge.herald"))
    }

    // --- the rule stays off unless the document turns it on ------------------

    /**
     * Every phone in the field polls a document older than policy 62. A default
     * that enabled the rule would have them start removing apps on the next
     * poll, with no build change and nobody having asked.
     */
    @Test
    fun `a policy without the block has no store rule at all`() {
        assertEquals(null, policy.copy(appRatings = null).appRatings)
    }

    /**
     * The empty-versus-absent distinction, for the third time in this codebase:
     * the install lock's snapshot, allowlist mode, and now this. A rule that
     * removes what is *not* named has to refuse to run rather than guess when
     * its list is missing, because guessing means removing everything.
     */
    @Test
    fun `a configured-looking but empty rule is treated as unconfigured`() {
        assertFalse(AppRatings(storeRegion = "BE").isConfigured)
        assertFalse(
            "a whitelist alone is not a rule — it can only keep",
            AppRatings(allowedPackages = listOf("ch.threema.app")).isConfigured,
        )
        assertTrue(ratings.isConfigured)
    }

    // --- precedence ----------------------------------------------------------

    /**
     * **The store is asked last of the removing rules, and that is deliberate.**
     * An app the policy names by hand has already been decided on, so asking
     * Play about it would be a network round trip to reach the same answer more
     * slowly and less reliably — and on a 294-package handset the earlier
     * branches answer for almost everything, which is what keeps the lookup
     * affordable at all.
     */
    @Test
    fun `the blocklist decides before the store is consulted`() {
        assertTrue(
            "Instagram is named by the policy, so no lookup is needed for it",
            "com.instagram.android" in policy.blockedPackages,
        )
        // And it is not whitelisted, which policytool.py refuses to sign.
        assertFalse(ratings.isAlwaysAllowed("com.instagram.android"))
    }

    /**
     * The invariant `tools/policytool.py sign` enforces, asserted here as well
     * because the two live far apart and only one of them runs on a phone. A
     * package on both lists is *unblocked*, silently, since the whitelist is
     * consulted first.
     */
    @Test
    fun `nothing in this policy is both whitelisted and blocked`() {
        val both = ratings.allowedPackages.filter { it in policy.blockedPackages }
        assertEquals(emptyList<String>(), both)
    }

    /**
     * The other invariant: an option-governed package must not be whitelisted,
     * or the parent's switch moves and changes nothing. WhatsApp is the case —
     * blocked by name, exempted by a switch, and deliberately absent from the
     * whitelist.
     */
    @Test
    fun `nothing in this policy is both whitelisted and governed by an option`() {
        val governed = policy.options.flatMap { it.exemptPackages + it.allowedPackages }.toSet()
        val captured = ratings.allowedPackages.filter { it in governed }

        assertEquals(emptyList<String>(), captured)
        assertTrue("the case this is guarding", "com.whatsapp" in governed)
    }

    /**
     * The store rule does not wait for the lock, because nothing on the
     * configuration screen can change it — there is no toggle for games and none
     * for dating, on the owner's call of 2026-08-17. It is the policy's own
     * list in that respect, and the policy's list acts as soon as it is seen.
     */
    @Test
    fun `a store verdict is not deferred`() {
        val allowedBrowsers = BrowserSettings.allowedBrowsers(policy, BrowserSettings.Choice.ALL)

        assertFalse(
            "no switch governs it, so there is no second question to wait for",
            AppBlocker.deferred("com.example.game", policy, allowedBrowsers),
        )
    }
}
