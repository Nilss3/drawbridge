package app.drawbridge.herald.bookmarks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.drawbridge.herald.BrowserActivity
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.applySystemBarInsets
import app.drawbridge.herald.ext.components
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.support.base.log.logger.Logger
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bookmarks: folders, search, editing, and import/export of a
 * `bookmarks.html` file.
 *
 * Unlike history and saved passwords this is not an
 * [app.drawbridge.herald.list.EntryListActivity] — that screen is a flat list
 * where a row can be opened or removed, and folder navigation, moving and file
 * transfer do not fit inside it. It borrows the same layout, which already
 * carries a toolbar, a search field and an empty state.
 */
class BookmarksActivity : AppCompatActivity() {

    private val repository by lazy { BookmarkRepository(components.core.bookmarksStorage) }

    private lateinit var toolbar: Toolbar
    private lateinit var searchField: EditText
    private lateinit var emptyView: TextView
    private lateinit var adapter: BookmarkListAdapter

    /** The path from the root to the folder on screen; never empty. */
    private val path = ArrayDeque<Crumb>()

    private var query: String = ""
    private var searchJob: Job? = null

    private data class Crumb(val guid: String, val title: String)

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri ->
            uri?.let { export(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_list)

        path.addLast(Crumb(repository.rootGuid, getString(R.string.menu_bookmarks)))

        findViewById<View>(R.id.root).applySystemBarInsets(top = true, bottom = true, sides = true)

        toolbar = findViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { goUp() }
            inflateMenu(R.menu.menu_bookmarks)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_folder -> newFolderDialog()
                    R.id.action_import -> importLauncher.launch(IMPORT_MIME)
                    R.id.action_export -> exportLauncher.launch(exportFileName())
                    R.id.action_clear_all -> confirmClearAll()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }

        emptyView = findViewById(R.id.emptyView)

        searchField = findViewById<EditText>(R.id.searchField).apply {
            visibility = View.VISIBLE
            setHint(R.string.bookmarks_search_hint)
            doAfterTextChanged { text ->
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    query = text?.toString().orEmpty().trim()
                    refresh()
                }
            }
        }

        adapter = BookmarkListAdapter(
            onClick = ::onRowClicked,
            onOverflow = ::showRowMenu,
        )

        findViewById<RecyclerView>(R.id.entryList).apply {
            layoutManager = LinearLayoutManager(this@BookmarksActivity)
            adapter = this@BookmarksActivity.adapter
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goUp()
            },
        )

        // Arrived from the browser menu's "Edit bookmark": go straight to the
        // dialog for the page that was on screen.
        if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_EDIT_URL)?.let { url ->
                lifecycleScope.launch {
                    repository.bookmarksFor(url)
                        .firstNotNullOfOrNull { BookmarkRow.from(it) }
                        ?.let { editDialog(it) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { refresh() }
    }

    // ------------------------------------------------------------ navigation

    /** Out of a folder, or out of the screen when already at the root. */
    private fun goUp() {
        if (path.size > 1) {
            path.removeLast()
            lifecycleScope.launch { refresh() }
        } else {
            finish()
        }
    }

    private fun onRowClicked(row: BookmarkRow) {
        if (row.isFolder) {
            path.addLast(Crumb(row.guid, row.title))
            // Descending out of a set of search results would land in a folder
            // still filtered by a query that has nothing to do with it.
            searchField.text.clear()
            query = ""
            lifecycleScope.launch { refresh() }
        } else {
            val url = row.url ?: return
            startActivity(
                Intent(this, BrowserActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
            )
            finish()
        }
    }

    private suspend fun refresh() {
        val current = path.last()
        val rows = if (query.isEmpty()) {
            BookmarkRow.from(repository.children(current.guid))
        } else {
            // Search covers the whole tree, not the folder on screen: a bookmark
            // you cannot find is exactly the one you filed somewhere and forgot.
            BookmarkRow.from(repository.search(query))
        }

        toolbar.title = if (query.isEmpty()) current.title else getString(R.string.bookmarks_search_results)
        adapter.submitList(rows)
        emptyView.setText(
            when {
                query.isNotEmpty() -> R.string.bookmarks_no_results
                path.size > 1 -> R.string.bookmarks_folder_empty
                else -> R.string.bookmarks_empty
            },
        )
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------------- editing

    private fun showRowMenu(row: BookmarkRow, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_bookmark_row, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> editDialog(row)
                    R.id.action_delete -> lifecycleScope.launch {
                        repository.delete(row.guid)
                        refresh()
                    }

                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }.show()
    }

    private fun editDialog(row: BookmarkRow) = lifecycleScope.launch {
        val excluded = if (row.isFolder) repository.descendantFolders(row.guid) + row.guid else emptySet()
        val folders = repository.folderPaths(getString(R.string.menu_bookmarks))
            .filterNot { it.guid in excluded }

        val view = layoutInflater.inflate(R.layout.dialog_bookmark_edit, null)
        val titleField = view.findViewById<EditText>(R.id.bookmarkTitleField)
        val urlField = view.findViewById<EditText>(R.id.bookmarkUrlField)
        val spinner = view.findViewById<Spinner>(R.id.bookmarkFolderSpinner)

        titleField.setText(row.title)
        if (row.isFolder) {
            urlField.visibility = View.GONE
        } else {
            urlField.setText(row.url)
        }

        spinner.adapter = ArrayAdapter(
            this@BookmarksActivity,
            android.R.layout.simple_spinner_dropdown_item,
            folders.map { it.label },
        )
        folders.indexOfFirst { it.guid == row.parentGuid }
            .takeIf { it >= 0 }
            ?.let { spinner.setSelection(it) }

        AlertDialog.Builder(this@BookmarksActivity)
            .setTitle(if (row.isFolder) R.string.bookmarks_edit_folder else R.string.bookmark_edit)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bookmarks_save) { _, _ ->
                val title = titleField.text.toString().trim()
                val url = if (row.isFolder) null else urlField.text.toString().trim()
                val parent = folders.getOrNull(spinner.selectedItemPosition)?.guid
                if (title.isEmpty() || (url != null && url.isEmpty())) {
                    toast(R.string.bookmarks_incomplete)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    repository.update(row.guid, title, url, parent)
                    refresh()
                }
            }
            .show()
    }

    private fun newFolderDialog() {
        val field = EditText(this).apply {
            setHint(R.string.bookmark_field_title)
            setSingleLine()
            val pad = resources.getDimensionPixelSize(R.dimen.dialog_field_padding)
            setPadding(pad, pad, pad, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks_new_folder)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bookmarks_create) { _, _ ->
                val title = field.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    repository.addFolder(path.last().guid, title)
                    refresh()
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks_clear_all_title)
            .setMessage(R.string.bookmarks_clear_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_clear_all) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAll()
                    while (path.size > 1) path.removeLast()
                    refresh()
                }
            }
            .show()
    }

    // ----------------------------------------------------- import and export

    private fun export(target: Uri) = lifecycleScope.launch {
        val message = try {
            val document = BookmarkHtml.write(repository.exportNodes())
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target)?.use { it.write(document.toByteArray()) }
                    ?: error("no output stream")
            }
            getString(R.string.bookmarks_exported)
        } catch (e: Exception) {
            // Anything the picker hands back can fail: a revoked permission, a
            // provider that has gone away, a full disk. None of it is worth a
            // crash in a bookmarks screen.
            logFailure("export", e)
            getString(R.string.bookmarks_transfer_failed)
        }
        toast(message)
    }

    private fun confirmImport(source: Uri) = lifecycleScope.launch {
        val parsed = try {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)?.use { BookmarkHtml.parse(readCapped(it)) }
                    ?: error("no input stream")
            }
        } catch (e: Exception) {
            logFailure("import", e)
            toast(R.string.bookmarks_transfer_failed)
            return@launch
        }

        val count = countItems(parsed.nodes)
        if (count == 0) {
            toast(R.string.bookmarks_import_nothing)
            return@launch
        }

        AlertDialog.Builder(this@BookmarksActivity)
            .setTitle(R.string.bookmarks_import)
            .setMessage(
                resources.getQuantityString(
                    if (parsed.truncated) {
                        R.plurals.bookmarks_import_confirm_truncated
                    } else {
                        R.plurals.bookmarks_import_confirm
                    },
                    count,
                    count,
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bookmarks_import_action) { _, _ ->
                lifecycleScope.launch { runImport(parsed.nodes) }
            }
            .show()
    }

    /**
     * Imports land in a folder of their own rather than merging into the tree,
     * so an import can be undone by deleting one folder and can never overwrite
     * what is already there.
     */
    private suspend fun runImport(nodes: List<BookmarkHtml.Node>) {
        val folderName = getString(R.string.bookmarks_imported_folder, dateStamp())
        val folder = repository.addFolder(repository.rootGuid, folderName)
        if (folder == null) {
            toast(R.string.bookmarks_transfer_failed)
            return
        }
        val imported = repository.importInto(folder, nodes)
        while (path.size > 1) path.removeLast()
        searchField.text.clear()
        query = ""
        refresh()
        toast(resources.getQuantityString(R.plurals.bookmarks_imported, imported, imported))
    }

    private fun countItems(nodes: List<BookmarkHtml.Node>): Int = nodes.sumOf { node ->
        when (node) {
            is BookmarkHtml.Node.Item -> 1
            is BookmarkHtml.Node.Folder -> countItems(node.children)
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * The picked file, up to [BookmarkHtml.MAX_INPUT_CHARS]. Read in chunks
     * rather than whole so that choosing a multi-gigabyte file by mistake costs
     * a truncated import instead of the process.
     */
    private fun readCapped(stream: InputStream): String {
        val text = StringBuilder()
        val chunk = CharArray(READ_CHUNK_CHARS)
        stream.bufferedReader().use { reader ->
            while (text.length < BookmarkHtml.MAX_INPUT_CHARS) {
                val read = reader.read(chunk)
                if (read < 0) break
                text.append(chunk, 0, minOf(read, BookmarkHtml.MAX_INPUT_CHARS - text.length))
            }
        }
        return text.toString()
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun logFailure(what: String, error: Throwable) {
        logger.warn("Bookmark $what failed", error)
    }

    private fun exportFileName(): String = "herald-bookmarks-${dateStamp()}.html"

    private fun dateStamp(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val EXTRA_EDIT_URL = "edit_url"

        /** Opens the screen with the edit dialog already showing for [url]. */
        fun editIntent(context: Context, url: String): Intent =
            Intent(context, BookmarksActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_EDIT_URL, url)
            }

        private val logger = Logger("herald-bookmarks")

        private const val SEARCH_DEBOUNCE_MS = 200L
        private const val READ_CHUNK_CHARS = 8 * 1024
        private const val EXPORT_MIME = "text/html"

        /**
         * Files exported by other browsers are not reliably labelled
         * `text/html` by the provider that hands them over, so the picker is
         * given the types they actually turn up as.
         */
        private val IMPORT_MIME = arrayOf(
            "text/html",
            "application/xhtml+xml",
            "text/plain",
            "application/octet-stream",
        )
    }
}
