package app.drawbridge.herald.bookmarks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.concept.storage.BookmarkInfo
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType

/**
 * The bookmark tree, as the browser wants to use it.
 *
 * [PlacesBookmarksStorage] returns `Result<T>` from every read and runs on
 * whatever dispatcher it is called from. This unwraps both once, so no screen
 * deals with a `Result` or has to remember to leave the main thread — a failed
 * read is an empty list, which is what every caller here would do with it
 * anyway.
 *
 * Everything herald writes lives under [BookmarkRoot.Mobile]. The desktop roots
 * (menu, toolbar, unfiled) exist in the Places schema but herald never syncs, so
 * nothing ever appears in them; an import folds into Mobile too.
 */
class BookmarkRepository(private val storage: PlacesBookmarksStorage) {

    /** The folder every screen starts in. */
    val rootGuid: String get() = BookmarkRoot.Mobile.id

    /** One level: the folder itself, with its direct children attached. */
    suspend fun folder(guid: String): BookmarkNode? = io {
        storage.getTree(guid, recursive = false).getOrNull()
    }

    /** The whole subtree under [guid]. Used for export and for "clear all". */
    suspend fun tree(guid: String = rootGuid): BookmarkNode? = io {
        storage.getTree(guid, recursive = true).getOrNull()
    }

    suspend fun children(guid: String): List<BookmarkNode> = folder(guid)?.children.orEmpty()

    /**
     * Matches on title and URL across every folder. Places only searches items,
     * so folders never appear in the results.
     */
    suspend fun search(query: String): List<BookmarkNode> = io {
        storage.searchBookmarks(query, SEARCH_LIMIT).getOrNull().orEmpty()
    }

    suspend fun bookmarksFor(url: String): List<BookmarkNode> = io {
        storage.getBookmarksWithUrl(url).getOrNull().orEmpty()
    }

    suspend fun addItem(parentGuid: String, url: String, title: String): String? = io {
        storage.addItem(parentGuid, url, title.ifBlank { url }, position = null).getOrNull()
    }

    suspend fun addFolder(parentGuid: String, title: String): String? = io {
        storage.addFolder(parentGuid, title, position = null).getOrNull()
    }

    suspend fun update(guid: String, title: String, url: String?, parentGuid: String?) = io {
        storage.updateNode(
            guid,
            BookmarkInfo(parentGuid = parentGuid, position = null, title = title, url = url),
        )
        Unit
    }

    suspend fun delete(guid: String) = io {
        storage.deleteNode(guid)
        Unit
    }

    /** Everything under Mobile, leaving the root folder itself in place. */
    suspend fun deleteAll() = io {
        children(rootGuid).forEach { storage.deleteNode(it.guid) }
    }

    /**
     * Every folder under the root, depth-first, each labelled with its path —
     * what the "move to folder" picker offers. The root itself is first.
     */
    suspend fun folderPaths(rootTitle: String): List<FolderPath> {
        val root = tree(rootGuid) ?: return listOf(FolderPath(rootGuid, rootTitle))
        val paths = mutableListOf(FolderPath(rootGuid, rootTitle))
        fun walk(node: BookmarkNode, prefix: String) {
            node.children.orEmpty()
                .filter { it.type == BookmarkNodeType.FOLDER }
                .forEach { child ->
                    val label = prefix + (child.title.orEmpty().ifBlank { child.guid })
                    paths += FolderPath(child.guid, label)
                    walk(child, "$label / ")
                }
        }
        walk(root, "")
        return paths
    }

    /** A folder as offered by the picker: its guid, and its path for display. */
    data class FolderPath(val guid: String, val label: String)

    /**
     * Every folder inside [guid]. A folder cannot be moved into one of its own
     * descendants, so the picker leaves these out.
     */
    suspend fun descendantFolders(guid: String): Set<String> {
        val root = tree(guid) ?: return emptySet()
        val found = mutableSetOf<String>()
        fun walk(node: BookmarkNode) {
            node.children.orEmpty()
                .filter { it.type == BookmarkNodeType.FOLDER }
                .forEach {
                    found += it.guid
                    walk(it)
                }
        }
        walk(root)
        return found
    }

    // -------------------------------------------------------------- transfer

    /** The Mobile subtree, shaped for [BookmarkHtml.write]. */
    suspend fun exportNodes(): List<BookmarkHtml.Node> = toHtmlNodes(children(rootGuid))

    /**
     * Writes a parsed document under [parentGuid], returning how many bookmarks
     * landed. Folders are recreated as folders.
     */
    suspend fun importInto(parentGuid: String, nodes: List<BookmarkHtml.Node>): Int {
        var imported = 0
        for (node in nodes) {
            when (node) {
                is BookmarkHtml.Node.Item ->
                    if (addItem(parentGuid, node.url, node.title) != null) imported++

                is BookmarkHtml.Node.Folder ->
                    addFolder(parentGuid, node.title)?.let { guid ->
                        imported += importInto(guid, node.children)
                    }
            }
        }
        return imported
    }

    /**
     * Folders with no title are flattened into their parent rather than
     * exported as a nameless heading, which no browser would import usefully.
     */
    private suspend fun toHtmlNodes(nodes: List<BookmarkNode>): List<BookmarkHtml.Node> =
        nodes.flatMap { node ->
            when (node.type) {
                BookmarkNodeType.FOLDER -> {
                    val children = toHtmlNodes(children(node.guid))
                    val title = node.title?.takeIf { it.isNotBlank() }
                    if (title == null) {
                        children
                    } else {
                        listOf(
                            BookmarkHtml.Node.Folder(title, children, node.dateAdded / MILLIS_PER_SECOND),
                        )
                    }
                }

                BookmarkNodeType.ITEM -> node.url?.let { url ->
                    listOf(
                        BookmarkHtml.Node.Item(
                            title = node.title?.takeIf { it.isNotBlank() } ?: url,
                            url = url,
                            addedAtSeconds = node.dateAdded / MILLIS_PER_SECOND,
                        ),
                    )
                }.orEmpty()

                BookmarkNodeType.SEPARATOR -> emptyList()
            }
        }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val SEARCH_LIMIT = 200
        const val MILLIS_PER_SECOND = 1000L
    }
}
