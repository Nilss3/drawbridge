package app.drawbridge.herald.downloads

import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.list.EntryListActivity
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.content.DownloadState.Status
import mozilla.components.lib.state.ext.flow

/**
 * The downloads list every browser has: newest first, tap to open, and a line
 * under each name saying how big it is, where it came from, or how far along it
 * is. Asked for by a user on 2026-10-14.
 *
 * **The rows are the browser store's, not a database of this screen's own.**
 * `DownloadMiddleware` already persists every download; this screen asks it to
 * put them back into the store when it opens.
 *
 * **Removing a row keeps the file.** The middleware is configured never to
 * delete from storage (see `Core.store`), so "remove" and "clear all" forget the
 * entry and leave the file where the user can still find it with the phone's
 * own Files app — the same thing Chrome and Firefox do.
 */
class DownloadsActivity : EntryListActivity() {

    override val titleResId = R.string.menu_downloads
    override val emptyMessageResId = R.string.downloads_empty
    override val searchHintResId = R.string.downloads_search_hint
    override val noResultsMessageResId = R.string.downloads_no_results
    override val clearAllTitleResId = R.string.downloads_clear_all_title
    override val clearAllMessageResId = R.string.downloads_clear_all_message

    private val store by lazy { components.core.store }
    private val fileUtils by lazy { components.downloads.fileUtils }
    private val sizeFormatter by lazy { components.downloads.fileSizeFormatter }
    private val useCases by lazy { components.useCases.downloadsUseCases }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The middleware saves every download but only puts them back into the
        // store when asked. Without this the list was empty after every restart
        // of herald — found on the emulator, where reinstalling restarts it.
        // Asked here rather than at start so the browser's own startup does not
        // pay for a database read nobody needs until this screen opens; a
        // restore is keyed by id, so asking again on the next visit is harmless.
        useCases.restoreDownloads()
        // A download in progress changes its own row; the base screen only
        // reloads when something on it is touched.
        lifecycleScope.launch {
            store.flow()
                .map { it.downloads }
                .distinctUntilChangedBy { downloads ->
                    downloads.values.map { Triple(it.id, it.status, it.currentBytesCopied / PROGRESS_STEP) }
                }
                .collect { reload() }
        }
    }

    override suspend fun loadEntries(query: String): List<Entry> =
        store.state.downloads.values
            .filterNot { it.private }
            .filter { query.isEmpty() || it.displayName().contains(query, ignoreCase = true) }
            .sortedByDescending { it.createdTime }
            .map { Entry(id = it.id, primary = it.displayName(), secondary = it.describe()) }

    override fun onEntryClicked(entry: Entry) {
        val download = store.state.downloads[entry.id] ?: return
        if (download.status != Status.COMPLETED) return
        val name = download.fileName
        // Two different failures, worth telling apart: the file is gone, or it is
        // there and nothing on the phone opens that kind of file. The emulator
        // has no zip handler, which is how the second one was found.
        val message = when {
            name == null || !fileUtils.fileExists(download.directoryPath, name) ->
                R.string.downloads_file_missing
            runCatching {
                fileUtils.openFile(name, download.directoryPath, download.contentType)
            }.getOrDefault(false) -> return
            else -> R.string.downloads_no_app
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override suspend fun deleteEntry(entry: Entry) {
        useCases.removeDownload(entry.id)
    }

    override suspend fun deleteAll() {
        useCases.removeAllDownloads()
    }

    private fun DownloadState.displayName(): String =
        fileName?.takeIf { it.isNotBlank() } ?: url.toUri().lastPathSegment ?: url

    /** Size and site once finished; otherwise what is happening to it. */
    private fun DownloadState.describe(): String {
        val host = (referrerUrl ?: url).toUri().host.orEmpty()
        return when (status) {
            Status.COMPLETED -> listOfNotNull(
                contentLength?.takeIf { it > 0 }?.let(sizeFormatter::formatSizeInBytes),
                host.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            Status.DOWNLOADING -> progress?.let {
                getString(R.string.downloads_status_progress, (it * 100).toInt())
            } ?: getString(R.string.downloads_status_downloading)
            Status.INITIATED -> getString(R.string.downloads_status_downloading)
            Status.PAUSED -> getString(R.string.downloads_status_paused)
            Status.FAILED -> getString(R.string.downloads_status_failed)
            Status.CANCELLED -> getString(R.string.downloads_status_cancelled)
        }
    }

    private companion object {
        /** Reload every 256 KiB of progress, not on every chunk. */
        const val PROGRESS_STEP = 256 * 1024L
    }
}
