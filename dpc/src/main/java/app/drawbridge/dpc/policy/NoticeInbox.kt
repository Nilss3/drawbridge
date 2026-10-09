package app.drawbridge.dpc.policy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.drawbridge.policy.model.PolicyNotice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every message this project has sent to this phone, newest first.
 *
 * **It is an inbox rather than a flag, and that is the whole design.** The
 * document carries at most one notice, and a document that carries a second one
 * has, by that fact, withdrawn the first — so a phone that only ever read
 * `policy.notice` would show each message for as long as it happened to be the
 * current one and then lose it for good. Somebody whose phone was off, or who
 * dismissed a notification on the bus, would have no way back to the words. The
 * document is the *delivery*; this is the record.
 *
 * **There is no read/unread state, and there was until the card went.** An
 * earlier version drew a dismissible card on drawbridge's own screens, so the
 * inbox had to remember which messages had been dismissed. With the card gone
 * the only surfaces are the notification — which the shade lets somebody swipe
 * away, and which Android already tracks for us — and this archive, which lists
 * everything. Nothing is left that a `dismissed` flag could hide, and a flag
 * with nothing to hide is a field that rots.
 *
 * **Time order is the phone's own clock, not the document's.** There is
 * deliberately no date field on [PolicyNotice] to get wrong or to disagree
 * across a fleet: what is recorded is when *this* phone first saw the message,
 * which is the only honest thing it knows and is what the screen sorts on. A
 * phone that was off for a week receives three months of nothing and then one
 * notice dated the day it came back, which is correct — that is when it arrived.
 */
class NoticeInbox(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** One message as it reached this phone. */
    @Serializable
    data class Received(
        val notice: PolicyNotice,
        /** Epoch millis when this phone first saw it. See the class note. */
        @SerialName("received_at")
        val receivedAt: Long,
    )

    /**
     * Files [notice] if it has not been seen before.
     *
     * @return the new entry when this is the first sight of it — the caller's
     *   cue to raise a notification — or null when there is nothing new, which
     *   is the answer on all but a handful of days in this project's life.
     */
    fun record(notice: PolicyNotice?): Received? {
        if (notice == null || !notice.isDrawable()) return null

        val held = all()
        if (held.any { it.notice.id == notice.id }) return null
        if (notice.id in acknowledged()) return null

        val entry = Received(notice = notice, receivedAt = System.currentTimeMillis())
        // Newest first on disk as well as in memory, so the screen can render
        // what it reads without sorting and a truncated file still holds the
        // messages that matter most.
        write(listOf(entry) + held)
        Log.i(TAG, "Notice ${notice.id} received")
        return entry
    }

    /**
     * Marks [notice] as seen without filing it, so that [record] never reports it
     * as new.
     *
     * **For the message that was already current when this phone joined.** The
     * inbox of a freshly installed drawbridge is empty, so without this the
     * first document it reads — bundled or fetched — rings for whatever notice
     * that document happens to carry. Policy 124 on dev did exactly that on a
     * factory-reset Moto G15 on 2026-10-09: *We can go live now*, a message to
     * the phones that were already there, delivered as news to one that had
     * existed for five minutes.
     *
     * Not filed either, because the archive is *what this project has said to
     * this phone*, and nothing was said to it: a notice is about the state of
     * the product when it was sent, and a phone installed afterwards is already
     * past that state.
     */
    fun acknowledge(notice: PolicyNotice?) {
        if (notice == null) return
        val seen = acknowledged()
        if (notice.id in seen) return
        prefs.edit().putStringSet(KEY_ACKNOWLEDGED, seen + notice.id).apply()
        Log.i(TAG, "Notice ${notice.id} predates this phone; acknowledged without filing")
    }

    private fun acknowledged(): Set<String> =
        prefs.getStringSet(KEY_ACKNOWLEDGED, null).orEmpty()

    /** Everything this phone has been sent, newest first. */
    fun all(): List<Received> {
        val stored = prefs.getString(KEY_RECEIVED, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Received>>(stored) }
            // A store this cannot read is a store written by a build that is
            // gone. Losing the history is survivable; refusing to open the
            // screen is not, and neither is crashing the always-on service that
            // files new ones.
            .onFailure { Log.e(TAG, "Unreadable notice history; starting over", it) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.receivedAt }
    }

    /** Part of the sanctioned removal flow, like every other device-local store. */
    fun clear() = prefs.edit().clear().apply()

    private fun write(entries: List<Received>) {
        val trimmed = entries.take(MAX_KEPT)
        prefs.edit().putString(KEY_RECEIVED, json.encodeToString(trimmed)).apply()
    }

    companion object {
        /**
         * Whether a notice carried by document [version] was already current
         * when this phone joined, and should be [acknowledge]d rather than
         * announced.
         *
         * **No download yet** means the document is the copy bundled in the APK,
         * which is behind the live one by design. **At or below the first
         * download** means the document is the one the phone found waiting on
         * its first poll — on 2026-10-09 the bundle was 122 with no notice and
         * the first download was 124 with one, so the bundle alone was not
         * enough. Anything newer arrived after the phone did, and is news.
         *
         * A null [firstDownloadedVersion] with a download on record is a phone
         * that was polling before the version was recorded. It keeps the old
         * behaviour: every new notice is news.
         */
        fun predatesThisPhone(
            version: Int,
            lastSuccessfulDownloadMillis: Long,
            firstDownloadedVersion: Int?,
        ): Boolean =
            lastSuccessfulDownloadMillis == 0L ||
                (firstDownloadedVersion != null && version <= firstDownloadedVersion)

        private const val TAG = "NoticeInbox"
        private const val PREFS_NAME = "drawbridge_notice"
        private const val KEY_RECEIVED = "received"
        private const val KEY_ACKNOWLEDGED = "acknowledged"

        /**
         * A ceiling rather than a policy. At the rate this channel is meant to
         * be used it will never be reached; it is here so that a bug at the
         * sending end cannot grow a preferences file without bound on a phone
         * nobody is looking at.
         */
        private const val MAX_KEPT = 50

        /**
         * `ignoreUnknownKeys` earns its place here rather than being habit: an
         * archive written by a build that had the dismissal flag still carries
         * `dismissed`, and a phone updating to a build without it must read its
         * own history rather than throw it away.
         */
        private val json = Json { ignoreUnknownKeys = true }
    }
}
