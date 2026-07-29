package app.drawbridge.herald.history

import android.content.Intent
import app.drawbridge.herald.BrowserActivity
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.list.EntryListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryActivity : EntryListActivity() {

    override val titleResId = R.string.menu_history
    override val emptyMessageResId = R.string.history_empty
    override val searchHintResId = R.string.history_search_hint
    override val noResultsMessageResId = R.string.history_no_results

    private val storage by lazy { components.core.historyStorage }

    /**
     * With no query this is the most recent page of visits. With one it is
     * `getSuggestions`, which matches on title as well as URL and searches the
     * whole of history rather than the page that happens to be loaded — the
     * reason the search exists, since anything older than [PAGE_SIZE] visits is
     * otherwise unreachable.
     */
    override suspend fun loadEntries(query: String): List<Entry> = withContext(Dispatchers.IO) {
        if (query.isEmpty()) {
            storage.getVisitsPaginated(offset = 0, count = PAGE_SIZE)
                .map { visit ->
                    Entry(
                        id = "${visit.url}@${visit.visitTime}",
                        primary = visit.title?.takeIf { it.isNotBlank() } ?: visit.url,
                        secondary = visit.url,
                    )
                }
        } else {
            storage.getSuggestions(query, SEARCH_LIMIT)
                .map { result ->
                    Entry(
                        id = result.id,
                        primary = result.title?.takeIf { it.isNotBlank() } ?: result.url,
                        secondary = result.url,
                    )
                }
        }
    }

    override fun onEntryClicked(entry: Entry) {
        val url = entry.secondary ?: return
        startActivity(
            Intent(this, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
        finish()
    }

    override suspend fun deleteEntry(entry: Entry) = withContext(Dispatchers.IO) {
        val url = entry.secondary ?: return@withContext
        storage.deleteVisitsFor(url)
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        storage.deleteEverything()
    }

    private companion object {
        const val PAGE_SIZE = 500L
        const val SEARCH_LIMIT = 200
    }
}
