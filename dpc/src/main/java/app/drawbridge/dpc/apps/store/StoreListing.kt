package app.drawbridge.dpc.apps.store

/**
 * One package's answer from the Play Store, and the parser that reads it.
 *
 * **The parsing is the part that has gone wrong twice**, which is why it is a
 * pure function over a string rather than something entangled with the fetch.
 * Both failures were the same shape: the listing page carries two to four
 * *different* rating strings — the app's own, plus one per entry in the "similar
 * apps" carousel — so anything resembling a text search for `PEGI` returns a
 * neighbouring app's rating, confidently and wrongly. One of those mistakes
 * reported that `com.scatterlab.messenger` was rated PEGI 3 when it is
 * *Parental guidance*, and it looked like a finding rather than a bug.
 *
 * So: **never search the page text.** Anchor on one of the two structured
 * fields, and use their agreement as a free integrity check. Measured across 86
 * pages on 2026-08-16, both were present on every one and agreed on every one.
 */
data class StoreListing(
    val packageName: String,
    /** The store's rating, e.g. `PEGI 3`, or null if it could not be read. */
    val rating: String?,
    /** e.g. `GAME_CASUAL`, `DATING`, `PRODUCTIVITY`, or null. */
    val category: String?,
    /** Non-null when this is a failure rather than an answer. */
    val error: String? = null,
) {
    val isUsable: Boolean get() = error == null && rating != null

    companion object {
        /**
         * schema.org microdata, which Google emits for search engines to read.
         * Anchored on the `itemprop` rather than on the value, so a carousel
         * entry cannot match.
         */
        private val MICRODATA_RATING =
            Regex("""itemprop="contentRating"[^>]*>\s*<span>([^<]+)</span>""")

        /** The JSON-LD block, which carries the same claim a second time. */
        private val JSONLD_RATING = Regex(""""contentRating"\s*:\s*"([^"]+)"""")

        private val JSONLD_CATEGORY = Regex(""""applicationCategory"\s*:\s*"([^"]{1,40})"""")

        /**
         * Reads [html] for [packageName]. Never throws; a page it cannot make
         * sense of comes back as a failure, which every caller treats as
         * [app.drawbridge.policy.model.AppRatings.Verdict.UNVERIFIED].
         */
        fun parse(packageName: String, html: String): StoreListing {
            val micro = MICRODATA_RATING.find(html)?.groupValues?.get(1)?.trim()
            val jsonLd = JSONLD_RATING.find(html)?.groupValues?.get(1)?.trim()

            // **A disagreement is a parse failure, not a tie to be broken.** They
            // agreed on all 86 pages measured, so if they stop agreeing the page
            // shape has moved and this parser is no longer reading what it
            // believes it is. Picking one would be picking at random, and the
            // wrong pick removes somebody's app.
            if (!micro.isNullOrEmpty() && !jsonLd.isNullOrEmpty() && micro != jsonLd) {
                return StoreListing(
                    packageName = packageName,
                    rating = null,
                    category = null,
                    error = "rating anchors disagree: '$micro' vs '$jsonLd'",
                )
            }

            val rating = micro?.takeIf { it.isNotEmpty() } ?: jsonLd?.takeIf { it.isNotEmpty() }
            if (rating == null) {
                // Reached when the page rendered without the rating block at all
                // — a region where the listing is unavailable, or an error page
                // served with a 200.
                return StoreListing(packageName, null, null, error = "no rating field on the page")
            }

            return StoreListing(
                packageName = packageName,
                rating = rating,
                category = JSONLD_CATEGORY.find(html)?.groupValues?.get(1)?.trim(),
            )
        }

        /**
         * The listing URL for a package.
         *
         * `hl=en` is pinned so the rating *id* is stable — the title is
         * localised, and a policy table cannot be written against three
         * translations of it. `gl` is the policy's, because the rating itself is
         * regional. Both halves matter: the same package is *Mature 17+* in the
         * US and *Parental guidance* in Belgium.
         */
        fun url(packageName: String, region: String): String =
            "https://play.google.com/store/apps/details" +
                "?id=$packageName&hl=en&gl=$region"
    }
}
