package app.drawbridge.herald

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.drawbridge.policy.SelectionSource

/**
 * herald's link to drawbridge: which policy profile and which options the parent
 * chose on this phone.
 *
 * The two apps read the same signed document, but the document is not the whole
 * answer — a parent who switches "Allow WhatsApp" on has changed how that
 * document should be read, and only drawbridge knows they did. Before this, the
 * browser filtered on the document's defaults while the DNS layer filtered on
 * the parent's actual choice, so the switch appeared to do nothing in the
 * browser.
 *
 * On a phone without drawbridge there is no provider to query and this returns
 * null, which is exactly the standalone case: the browser follows the document's
 * own defaults. That is also the strict reading, so the failure mode of a
 * drawbridge that is missing, crashed or mid-upgrade is a browser that blocks
 * more than it needs to rather than less.
 *
 * The same provider also answers [connectivity], which is a different kind of
 * question and is read separately — see that function.
 */
class DrawbridgeSelection(context: Context) : SelectionSource {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun read(): SelectionSource.Selection? = try {
        resolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val profileId = cursor.columnOrNull(COLUMN_PROFILE_ID)
                val optionIds = cursor.columnOrNull(COLUMN_OPTION_IDS)
                SelectionSource.Selection(
                    profileId = profileId,
                    // An empty string is "the parent turned everything off",
                    // which is a choice; a missing column is "no answer", which
                    // is not. Only the second may fall back to the defaults.
                    optionIds = optionIds?.split(SEPARATOR)?.filter { it.isNotBlank() },
                )
            }
        }
    } catch (e: Exception) {
        // SecurityException on a build signed with a different key, and
        // IllegalArgumentException while drawbridge is being upgraded and its
        // provider is briefly gone. Neither is worth a crash in the browser.
        Log.d(TAG, "No drawbridge selection available", e)
        null
    }

    /**
     * Whether the phone is refusing IP traffic on purpose, and until when.
     *
     * Read on demand rather than cached: it is only ever consulted while an
     * error page is being built, and by then an answer from a second ago is
     * worth less than a fresh one — a curfew that ended a minute back is exactly
     * the case where a cached "offline" would be wrong.
     *
     * Null for every ordinary reason: no drawbridge, an older drawbridge whose
     * provider has no such columns, or a provider that threw. All three mean the
     * browser shows the error page it always showed, which is the right answer
     * when nothing can say otherwise.
     */
    fun connectivity(): Connectivity? = try {
        resolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getColumnIndex(COLUMN_OFFLINE_NOW) < 0) {
                null
            } else {
                Connectivity(
                    mode = cursor.columnOrNull(COLUMN_DISCONNECT_MODE),
                    offlineNow = cursor.columnOrNull(COLUMN_OFFLINE_NOW) == "1",
                    onlineAgainAt = cursor.columnOrNull(COLUMN_OFFLINE_UNTIL)?.toLongOrNull(),
                )
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "No drawbridge connectivity available", e)
        null
    }

    /**
     * @param mode which philosophy is chosen, as drawbridge names it.
     * @param offlineNow whether traffic is being refused at this moment.
     * @param onlineAgainAt epoch millis of the next change, or null when there
     *   is none — a phone that is offline for good, or not locked at all.
     */
    data class Connectivity(
        val mode: String?,
        val offlineNow: Boolean,
        val onlineAgainAt: Long?,
    ) {
        val isCurfew: Boolean get() = mode == "CURFEW"
    }

    private fun android.database.Cursor.columnOrNull(name: String): String? {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    /**
     * Runs [onChanged] whenever drawbridge publishes a new selection.
     *
     * Registering costs nothing when drawbridge is absent — the URI is just a
     * string until something notifies on it — so there is no need to check first.
     */
    fun observe(onChanged: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        runCatching { resolver.registerContentObserver(CONTENT_URI, false, observer) }
            .onFailure { Log.d(TAG, "Could not watch the drawbridge selection", it) }
    }

    private companion object {
        const val TAG = "herald-selection"

        // Duplicated from drawbridge rather than shared, because herald must
        // build and run without it. They are a published contract between two
        // separately installable apps; changing either side alone breaks it.
        const val AUTHORITY = "app.drawbridge.dpc.selection"
        const val COLUMN_PROFILE_ID = "profile_id"
        const val COLUMN_OPTION_IDS = "option_ids"
        const val COLUMN_DISCONNECT_MODE = "disconnect_mode"
        const val COLUMN_OFFLINE_NOW = "offline_now"
        const val COLUMN_OFFLINE_UNTIL = "offline_until"
        const val SEPARATOR = ","

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/selection")
    }
}
