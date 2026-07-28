package app.drawbridge.herald.bookmarks

import android.content.Intent
import app.drawbridge.herald.BrowserActivity
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.list.EntryListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.appservices.places.BookmarkRoot

class BookmarksActivity : EntryListActivity() {

    override val titleResId = R.string.menu_bookmarks
    override val emptyMessageResId = R.string.bookmarks_empty

    private val storage by lazy { components.core.bookmarksStorage }

    override suspend fun loadEntries(): List<Entry> = withContext(Dispatchers.IO) {
        val tree = storage.getTree(BookmarkRoot.Mobile.id, recursive = true).getOrNull()
        flatten(tree).map { node ->
            Entry(
                id = node.guid,
                primary = node.title?.takeIf { it.isNotBlank() } ?: node.url.orEmpty(),
                secondary = node.url,
            )
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

    override suspend fun deleteEntry(entry: Entry) {
        withContext(Dispatchers.IO) { storage.deleteNode(entry.id) }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            val tree = storage.getTree(BookmarkRoot.Mobile.id, recursive = true).getOrNull()
            flatten(tree).forEach { storage.deleteNode(it.guid) }
        }
    }

    private fun flatten(node: BookmarkNode?): List<BookmarkNode> {
        if (node == null) return emptyList()
        val children = node.children.orEmpty().flatMap { flatten(it) }
        return if (node.type == BookmarkNodeType.ITEM && node.url != null) {
            listOf(node) + children
        } else {
            children
        }
    }
}
