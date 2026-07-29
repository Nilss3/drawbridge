package app.drawbridge.herald.settings

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * The handful of things herald remembers about how it should behave.
 *
 * Kept in the default `PreferenceManager` store, the same one
 * [app.drawbridge.herald.search.SearchEngineSelection] uses, so the preference
 * screen binds to it without any of its own plumbing.
 */
object HeraldSettings {

    /** Show the bookmark list on a blank tab instead of an empty page. */
    const val KEY_NEW_TAB_BOOKMARKS = "new_tab_bookmarks"

    const val NEW_TAB_BOOKMARKS_DEFAULT = false

    fun showBookmarksOnNewTab(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_NEW_TAB_BOOKMARKS, NEW_TAB_BOOKMARKS_DEFAULT)
}
