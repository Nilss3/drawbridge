package app.drawbridge.dpc.apps.store

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.drawbridge.policy.model.AppRatings
import app.drawbridge.policy.net.Downloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the store says about the packages on this phone, remembered between
 * runs.
 *
 * ### The split that matters: reading is free, fetching is not
 *
 * [verdictFor] answers from the cache and **never touches the network**. It has
 * to: it is called from a broadcast receiver on an install and from a sweep over
 * every package on the device, and a rule that made an HTTPS request per package
 * would either block the filter service or spend a hundred megabytes finding out
 * that nothing had changed.
 *
 * [fetch] is the other half, and it is the only thing here that goes out. A
 * caller decides *when* — on an install, or once over the whole phone at the
 * first lock — and does it from a coroutine.
 *
 * ### What a lookup costs, measured
 *
 * A listing page is **~1.2 MB**, and the two fields sit **85–91% of the way into
 * it**, so a `Range` request saves nothing — that was measured on 2026-08-17 and
 * is recorded here so nobody tries it again. Forty to eighty user-installed apps
 * is therefore 50–100 MB for a first full pass, which is why that pass belongs on
 * an unmetered network and why this cache is durable rather than in-memory.
 *
 * ### Fail open, and count it
 *
 * Anything that goes wrong — offline, a 404, a page shape this build cannot
 * parse — is stored as a failure and read back as
 * [AppRatings.Verdict.UNVERIFIED], which every caller treats as *keep*.
 * drawbridge has no way to ask a parent on a locked phone, so the alternative to
 * keeping is uninstalling on a network blip. [stats] exists so the pile is
 * visible in Diagnostics rather than silent, which is the whole difference
 * between a deliberate fallback and a bug.
 */
class StoreCatalogue(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val lock = Any()

    @Volatile
    private var cache: MutableMap<String, Entry>? = null

    @Serializable
    private data class Entry(
        val rating: String? = null,
        val category: String? = null,
        val error: String? = null,
        /**
         * The app version this answer describes.
         *
         * The only signal a device gets that a listing may have been re-rated:
         * an app that updated is an app whose publisher has been back to the
         * console. Cheaper and sharper than shortening the TTL for everything.
         */
        val versionCode: Long = 0,
        val fetchedAt: Long = 0,
    )

    /**
     * The store's verdict on [packageName] under [ratings], from cache only.
     *
     * [AppRatings.Verdict.UNVERIFIED] for anything not cached, stale, or
     * recorded as a failure — all of which mean *keep*, and all of which are
     * counted by [stats].
     */
    fun verdictFor(packageName: String, ratings: AppRatings): AppRatings.Verdict {
        val entry = fresh(packageName) ?: return AppRatings.Verdict.UNVERIFIED
        if (entry.error != null) return AppRatings.Verdict.UNVERIFIED
        return ratings.verdict(entry.rating, entry.category)
    }

    /** True when [packageName] has a usable, current answer and needs no fetch. */
    fun isFresh(packageName: String): Boolean = fresh(packageName) != null

    /** What the store actually said, for a log line that names which half fired. */
    data class Answer(val rating: String?, val category: String?)

    /**
     * The cached answer, or null if there is not a current one.
     *
     * Exists so a removal can say *the store files it under GAME_CASUAL* rather
     * than *the store disagrees*. A log line that does not say which rule fired
     * is the line that cost 2026-08-14 an evening and a cable in a different
     * part of this class.
     */
    fun answerFor(packageName: String): Answer? =
        fresh(packageName)?.takeIf { it.error == null }?.let { Answer(it.rating, it.category) }

    private fun fresh(packageName: String): Entry? {
        val entry = load()[packageName] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > TTL_MILLIS) return null
        // An update may have been re-rated, so the old answer is not evidence.
        if (entry.versionCode != versionCodeOf(packageName)) return null
        return entry
    }

    /**
     * Fetches [packageName] and records whatever comes back, answer or failure.
     *
     * Blocking; call it off the main thread. Returns the verdict it just stored,
     * so a caller acting on one install does not have to read back.
     *
     * **A failure is cached too, and that is deliberate.** Without it a package
     * that is simply not on the Play Store — sideloaded, F-Droid, an OEM
     * extra — would be re-fetched on every sweep forever, which is a request
     * every fifteen minutes to be told the same 404. The short failure TTL means
     * a genuine outage still gets retried within the hour.
     */
    fun fetch(packageName: String, ratings: AppRatings): AppRatings.Verdict {
        val listing = runCatching {
            val html = Downloader(maxBytes = MAX_PAGE_BYTES)
                .getText(StoreListing.url(packageName, ratings.storeRegion))
            StoreListing.parse(packageName, html)
        }.getOrElse { error ->
            StoreListing(packageName, null, null, error = error.message ?: error.javaClass.simpleName)
        }

        if (listing.error != null) {
            Log.i(TAG, "No store answer for $packageName: ${listing.error}")
        }

        put(
            packageName,
            Entry(
                rating = listing.rating,
                category = listing.category,
                error = listing.error,
                versionCode = versionCodeOf(packageName),
                fetchedAt = System.currentTimeMillis() -
                    // A failure ages out far sooner than an answer: the answer is
                    // a fact about the app, the failure is usually a fact about
                    // the network at that moment.
                    if (listing.error != null) TTL_MILLIS - FAILURE_TTL_MILLIS else 0,
            ),
        )

        return if (listing.error != null) {
            AppRatings.Verdict.UNVERIFIED
        } else {
            ratings.verdict(listing.rating, listing.category)
        }
    }

    /** Forgets everything. Part of the sanctioned removal flow. */
    fun clear() {
        synchronized(lock) {
            cache = mutableMapOf()
            runCatching { file.delete() }
        }
    }

    /** Counts for Diagnostics. See the class comment on why they are reported. */
    data class Stats(
        val known: Int,
        val usable: Int,
        val failed: Int,
        val newestFetchMillis: Long,
    )

    fun stats(): Stats {
        val entries = load().values
        return Stats(
            known = entries.size,
            usable = entries.count { it.error == null },
            failed = entries.count { it.error != null },
            newestFetchMillis = entries.maxOfOrNull { it.fetchedAt } ?: 0L,
        )
    }

    /** The packages recorded as unanswerable, for the Diagnostics detail lines. */
    fun unverified(): List<String> =
        load().filterValues { it.error != null }.keys.sorted()

    private fun versionCodeOf(packageName: String): Long = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private fun load(): Map<String, Entry> {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: run {
                val loaded = runCatching {
                    if (file.exists()) {
                        JSON.decodeFromString<MutableMap<String, Entry>>(file.readText())
                    } else {
                        mutableMapOf()
                    }
                }.getOrElse {
                    // A cache this build cannot read is one to drop rather than
                    // one to fail over: it is derived data, and rebuilding costs
                    // requests rather than correctness.
                    Log.w(TAG, "Store cache unreadable; starting a new one", it)
                    mutableMapOf()
                }
                cache = loaded
                loaded
            }
        }
    }

    private fun put(packageName: String, entry: Entry) {
        synchronized(lock) {
            val current = load().toMutableMap()
            current[packageName] = entry
            cache = current
            runCatching { file.writeText(JSON.encodeToString(current)) }
                .onFailure { Log.w(TAG, "Could not write the store cache", it) }
        }
    }

    private companion object {
        const val TAG = "StoreCatalogue"
        const val FILE_NAME = "store-catalogue.json"

        /** Ratings change rarely; a version bump is the sharper signal. */
        val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Long enough not to hammer, short enough that an outage self-heals. */
        val FAILURE_TTL_MILLIS = 60L * 60 * 1000

        /** A listing is ~1.2 MB. Room to grow, far short of filling a phone. */
        const val MAX_PAGE_BYTES = 8L * 1024 * 1024

        val JSON = Json { ignoreUnknownKeys = true }
    }
}
