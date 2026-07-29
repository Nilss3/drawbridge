package app.drawbridge.herald.logins

import app.drawbridge.herald.R
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.list.EntryListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saved passwords.
 *
 * Only the site and username are ever displayed — there is no "reveal password"
 * action. Autofill still works, so nothing is lost functionally, and a device
 * that is by design not fully under its user's control is not a good place to
 * put a plaintext password on screen.
 */
class LoginsActivity : EntryListActivity() {

    override val titleResId = R.string.menu_passwords
    override val emptyMessageResId = R.string.logins_empty
    override val searchHintResId = R.string.logins_search_hint
    override val noResultsMessageResId = R.string.logins_no_results

    private val storage by lazy { components.core.loginsStorage }

    override suspend fun loadEntries(query: String): List<Entry> = withContext(Dispatchers.IO) {
        storage.list()
            .filter {
                query.isEmpty() ||
                    it.origin.contains(query, ignoreCase = true) ||
                    it.username.contains(query, ignoreCase = true)
            }
            .sortedBy { it.origin }
            .map { login ->
                Entry(
                    id = login.guid,
                    primary = login.origin,
                    secondary = login.username.ifEmpty { getString(R.string.logins_no_username) },
                )
            }
    }

    override suspend fun deleteEntry(entry: Entry) {
        withContext(Dispatchers.IO) { storage.delete(entry.id) }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            storage.list().forEach { storage.delete(it.guid) }
        }
    }
}
