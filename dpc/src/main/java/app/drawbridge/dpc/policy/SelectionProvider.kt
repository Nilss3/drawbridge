package app.drawbridge.dpc.policy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.curfew.DisconnectSettings
import app.drawbridge.dpc.security.ParentKey
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Publishes which policy profile and which options the parent chose, so that
 * herald filters on the same reading of the document as the DNS layer does.
 *
 * Without this the two disagreed. Both apps fetch the same signed policy, but
 * only drawbridge holds the *selection* — so the browser filtered on the
 * document's defaults while everything else on the phone filtered on what the
 * parent had actually switched on. The visible symptom was an option that
 * plainly did nothing: "Allow WhatsApp" on, and WhatsApp Web still blocked in
 * the browser.
 *
 * **Read-only, and signature-protected.** Both apps are signed with the same
 * release key, so `signature` grants herald the permission and nothing else on
 * the phone. There is no insert, update or delete: the selection is drawbridge's
 * to make, and a browser that could change it would be a way around the lock.
 *
 * A device without drawbridge simply has no provider here. herald treats that as
 * "no selection", which is the standalone case and the strict reading.
 *
 * ## It also publishes whether the phone is deliberately offline
 *
 * Added 2026-08-25. A curfew or the offline philosophy takes the network away by
 * the VPN's lockdown flag, and every app on the phone sees the same thing a
 * broken Wi-Fi looks like — so herald showed *server not found*, which is a lie
 * of omission on a phone that is doing exactly what it was told to. It already
 * has a proper page for a blocked site; there was nothing for a blocked hour.
 *
 * The three connectivity columns are what let it say so. They describe the
 * phone's *state*, not the policy: which philosophy is chosen, whether IP
 * traffic is being refused at this moment, and when that next changes. herald
 * uses them only to word an error page it was going to show anyway, so a stale
 * or missing answer costs a nicer sentence and nothing else.
 */
class SelectionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val policy = DrawbridgeApplication.policy(providerContext())

        // The *resolved* selection rather than the raw stored state: drawbridge
        // and herald can briefly hold different policy versions, and resolving
        // here means the browser is told an answer that is true of the document
        // the parent was actually looking at.
        val context = providerContext()
        val disconnect = DisconnectSettings(context)
        val now = LocalDateTime.now()

        // The same two questions CurfewController.apply asks, in the same order,
        // and for the same reason: the mode alone does not say whether the
        // network is gone right now. A curfew outside its hours is online, and
        // nothing is enforced at all until the phone is locked.
        val offlineNow = ParentKey(context).isLocked && disconnect.isOfflineAt(now)
        val until = if (ParentKey(context).isLocked) disconnect.nextChangeAfter(now) else null

        val cursor = MatrixCursor(
            arrayOf(
                COLUMN_PROFILE_ID,
                COLUMN_OPTION_IDS,
                COLUMN_DISCONNECT_MODE,
                COLUMN_OFFLINE_NOW,
                COLUMN_OFFLINE_UNTIL,
            ),
        )
        // arrayOf<Any?> rather than arrayOf: the row mixes strings, an int and a
        // nullable long, and letting Kotlin infer the element type reifies an
        // intersection of Comparable and Serializable.
        cursor.addRow(
            arrayOf<Any?>(
                policy.selectedProfile?.id,
                policy.enabledOptionIds.joinToString(SEPARATOR),
                disconnect.mode.name,
                if (offlineNow) 1 else 0,
                until?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.selection"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun providerContext(): Context =
        context ?: error("SelectionProvider queried before onCreate")

    companion object {
        const val AUTHORITY = "app.drawbridge.dpc.selection"

        const val COLUMN_PROFILE_ID = "profile_id"
        const val COLUMN_OPTION_IDS = "option_ids"

        /** [DisconnectSettings.Mode]'s name: ONLINE, CURFEW or OFFLINE. */
        const val COLUMN_DISCONNECT_MODE = "disconnect_mode"

        /** 1 while IP traffic is being refused, 0 otherwise. */
        const val COLUMN_OFFLINE_NOW = "offline_now"

        /**
         * Epoch millis at which the answer above next changes, or null when
         * nothing is scheduled — an unlocked phone, or one that is offline for
         * good.
         */
        const val COLUMN_OFFLINE_UNTIL = "offline_until"

        /** Option ids arrive as one string; no id contains a comma. */
        const val SEPARATOR = ","

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/selection")

        /**
         * Tells whoever is watching that the selection changed.
         *
         * Call it after every change drawbridge makes, or herald keeps filtering
         * on the previous answer until its next policy poll — up to a day of a
         * switch appearing to do nothing.
         */
        fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(CONTENT_URI, null)
        }
    }
}
