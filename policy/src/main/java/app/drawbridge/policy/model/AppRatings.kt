package app.drawbridge.policy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which apps a managed phone will accept, expressed against the store's own
 * content rating and category instead of a list of package names.
 *
 * A curated blocklist is a filter for a phone whose app store is wide open: new
 * apps in a category appear faster than a signed document can name them, which
 * is what policy 59 cost an afternoon proving. This is the rule that goes
 * upstream of the list. See docs/app-ratings.md for the measurements it is built
 * on; the short version is that useful apps are almost never rated above PEGI 3,
 * while the apps this project exists to keep off a phone almost always are.
 *
 * **It lives in the signed document rather than in the app** so a threshold can
 * move with a policy re-sign. That matters more here than elsewhere: Play
 * Protect refuses to install this DPC, so a build cannot be pushed to a handset
 * unaided, and a rule compiled into the APK would be a rule nobody could
 * correct. It is also what lets the region be set per deployment — the rule is
 * PEGI-shaped, and a phone whose store answers in ESRB labels needs different
 * ids rather than different code.
 */
@Serializable
data class AppRatings(
    /**
     * The storefront the rating is read from, as a two-letter region.
     *
     * **Not cosmetic, and not the device's locale.** The same package carries
     * different ratings in different markets — `com.scatterlab.messenger` is
     * *Mature 17+* in the United States and *Parental guidance* in Belgium — so
     * a policy's thresholds only mean anything against the storefront they were
     * written for. Pinning it in the document keeps the two together.
     */
    @SerialName("store_region")
    val storeRegion: String = "BE",

    /**
     * Rating ids that pass, lowercased. Everything else is removed, *including
     * `parental guidance`*.
     *
     * That band is the expensive half of the rule and it is deliberate. IARC
     * assigns it where it grades nothing, so it holds TikTok Lite and a
     * birdwatching app for the same reason — neither has been graded. Measured
     * on 2026-08-17: allowing it would let through 42 of 103 uncurated
     * harm-adjacent apps, including every anonymous-confession and
     * stranger-livestream app found. Blocking it costs a wider [allowedPackages]
     * and buys a narrower blocklist, which is the trade drawbridge wants —
     * a household's needs can be written down, and next month's confession apps
     * cannot.
     */
    @SerialName("allowed_ratings")
    val allowedRatings: List<String> = emptyList(),

    /**
     * Category prefixes that are removed outright — `GAME_` covers
     * `GAME_CASUAL`, `GAME_ARCADE` and whatever Google adds next.
     *
     * **Games are a category and not a rating, and that is the finding rather
     * than a convenience.** Candy Crush, Royal Match, Gardenscapes and FIFA
     * Mobile are all PEGI 3 while Minecraft and Pokémon GO are PEGI 7, because
     * PEGI grades content harm and cannot see a variable-ratio reward schedule.
     * A rating gate alone builds a phone with Candy Crush on it but not
     * Minecraft.
     */
    @SerialName("blocked_category_prefixes")
    val blockedCategoryPrefixes: List<String> = emptyList(),

    /** Whole categories removed outright. `DATING` is the one this ships with. */
    @SerialName("blocked_categories")
    val blockedCategories: List<String> = emptyList(),

    /**
     * Packages kept whatever the store says about them.
     *
     * **This is what pays for blocking `parental guidance`**, and it is the
     * reason that decision is affordable. Every private messenger carries that
     * rating, because ungraded conversation is exactly what it means, and a
     * phone that loses Threema and Session to a content rule is a phone nobody
     * will keep. So are conferencing apps, sport trackers with a community feed,
     * recipe apps, and the general-purpose AI assistants — which are here
     * because drawbridge cannot honestly claim to block AI chat while every
     * allowed browser reaches an assistant through its search box.
     *
     * **It can only keep.** It cannot remove anything and it must not be used
     * to. Two things it must never contain, both enforced by
     * `tools/policytool.py sign` because neither is visible by hand once this is
     * a few dozen names long: a package `blockedPackages` also names, which this
     * would silently unblock; and a package an option governs, which would leave
     * the parent's switch moving and changing nothing.
     */
    @SerialName("allowed_packages")
    val allowedPackages: List<String> = emptyList(),
) {

    /** What the store's answer means for one package. */
    enum class Verdict {
        /** The rating and category are both acceptable. */
        ALLOWED,

        /** Policy will not have it. */
        BLOCKED,

        /**
         * No usable answer — not on the store, unreachable, or a page this build
         * could not parse.
         *
         * **Treated as [ALLOWED] by every caller, deliberately.** drawbridge has
         * no way to ask a parent on a locked phone: there is no prompt, no PIN
         * and no screen, so the only outcomes available are keep and remove.
         * Removing on a failed lookup would mean a network blip uninstalling the
         * phone's apps, and would take every legitimately sideloaded app on
         * principle. It is kept apart from [ALLOWED] so that it can be *counted*
         * — a silently growing pile of unverified apps is the failure this
         * distinction exists to make visible.
         */
        UNVERIFIED,
    }

    /**
     * The rule, as a pure function of the store's two answers.
     *
     * Expressed against plain strings rather than a listing object or a network
     * call, for the same reason [app.drawbridge.policy.model.Curfew.isActiveAt]
     * is: it decides what gets uninstalled from somebody's phone, so it must be
     * checkable without holding one. Every threshold in this class has been
     * moved at least once on measurement, and each move was safe only because
     * this was a table rather than a code path.
     *
     * **Category is asked before rating.** They disagree on exactly the case the
     * category exists for — `AIKO: AI Girlfriend 3D Game` is PEGI 3 and
     * `GAME_SIMULATION`, Candy Crush is PEGI 3 and `GAME_CASUAL` — and asking
     * the rating first would let both through.
     */
    fun verdict(rating: String?, category: String?): Verdict {
        val normalisedCategory = category?.trim()?.uppercase()
        if (!normalisedCategory.isNullOrEmpty()) {
            if (blockedCategoryPrefixes.any { normalisedCategory.startsWith(it.uppercase()) }) {
                return Verdict.BLOCKED
            }
            if (blockedCategories.any { it.equals(normalisedCategory, ignoreCase = true) }) {
                return Verdict.BLOCKED
            }
        }

        val normalisedRating = rating?.trim()?.lowercase()
        // An absent rating is not a lenient one. The store answered without the
        // field this rule turns on, so there is no verdict to give.
        if (normalisedRating.isNullOrEmpty()) return Verdict.UNVERIFIED

        return if (allowedRatings.any { it.trim().equals(normalisedRating, ignoreCase = true) }) {
            Verdict.ALLOWED
        } else {
            Verdict.BLOCKED
        }
    }

    /** True if this policy names [packageName] as kept regardless of the store. */
    fun isAlwaysAllowed(packageName: String): Boolean = packageName in allowedPackages

    /**
     * Whether the rule has anything to say at all.
     *
     * A document with no `allowed_ratings` cannot express "these pass", and
     * treating an empty list as "nothing passes" would remove every app on the
     * phone. Same shape as the install lock's absent-versus-empty snapshot, and
     * the same reason: a rule that removes what is *not* named has to refuse to
     * run rather than guess when its list is missing.
     */
    val isConfigured: Boolean
        get() = allowedRatings.isNotEmpty() ||
            blockedCategories.isNotEmpty() ||
            blockedCategoryPrefixes.isNotEmpty()
}
