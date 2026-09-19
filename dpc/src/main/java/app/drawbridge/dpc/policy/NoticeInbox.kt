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
 * **The card and this file answer different questions**, which is why both
 * exist. `NoticeCard` asks *is there something the project is saying right now*
 * and reads the live document, so withdrawing a notice removes the card. The
 * notices screen asks *what has this project ever said to me* and reads this, so
 * nothing published is ever silently un-published from the parent's side.
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
        val dismissed: Boolean = false,
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

        val entry = Received(notice = notice, receivedAt = System.currentTimeMillis())
        // Newest first on disk as well as in memory, so the screen can render
        // what it reads without sorting and a truncated file still holds the
        // messages that matter most.
        write(listOf(entry) + held)
        Log.i(TAG, "Notice ${notice.id} received")
        return entry
    }

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

    /**
     * Whether [notice] should be drawn on a screen's card.
     *
     * Takes the *live document's* notice rather than reading the history, so a
     * withdrawn notice stops being shown. Null is the resting state of the
     * field and the common answer here.
     */
    fun shouldShow(notice: PolicyNotice?): Boolean {
        if (notice == null || !notice.isDrawable()) return false
        return all().none { it.notice.id == notice.id && it.dismissed }
    }

    /**
     * Records that the parent has dismissed [notice].
     *
     * Marks the entry rather than deleting it: dismissing is *I have read this*,
     * not *destroy it*, and the notices screen goes on listing it. A notice that
     * was dismissed before it was ever filed — possible only if the history was
     * cleared underneath it — is filed now, so the dismissal has something to
     * attach to.
     */
    fun dismiss(notice: PolicyNotice) {
        record(notice)
        write(all().map { if (it.notice.id == notice.id) it.copy(dismissed = true) else it })
    }

    /** Part of the sanctioned removal flow, like every other device-local store. */
    fun clear() = prefs.edit().clear().apply()

    private fun write(entries: List<Received>) {
        val trimmed = entries.take(MAX_KEPT)
        prefs.edit().putString(KEY_RECEIVED, json.encodeToString(trimmed)).apply()
    }

    private companion object {
        const val TAG = "NoticeInbox"
        const val PREFS_NAME = "drawbridge_notice"
        const val KEY_RECEIVED = "received"

        /**
         * A ceiling rather than a policy. At the rate this channel is meant to
         * be used it will never be reached; it is here so that a bug at the
         * sending end cannot grow a preferences file without bound on a phone
         * nobody is looking at.
         */
        const val MAX_KEPT = 50

        val json = Json { ignoreUnknownKeys = true }
    }
}
