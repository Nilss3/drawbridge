package app.drawbridge.dpc.policy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.drawbridge.dpc.DrawbridgeApplication

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
        val cursor = MatrixCursor(arrayOf(COLUMN_PROFILE_ID, COLUMN_OPTION_IDS))
        cursor.addRow(
            arrayOf(
                policy.selectedProfile?.id,
                policy.enabledOptionIds.joinToString(SEPARATOR),
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
