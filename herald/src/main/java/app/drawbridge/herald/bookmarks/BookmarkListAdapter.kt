package app.drawbridge.herald.bookmarks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.drawbridge.herald.R
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType

/** A folder or a bookmark, as shown in a list. */
data class BookmarkRow(
    val guid: String,
    val title: String,
    /** Null for a folder. */
    val url: String?,
    val parentGuid: String?,
) {
    val isFolder: Boolean get() = url == null

    companion object {
        /**
         * Separators and any node with neither a title nor a URL are dropped:
         * an imported file can contain both, and neither has a row to draw.
         */
        fun from(node: BookmarkNode): BookmarkRow? = when (node.type) {
            BookmarkNodeType.FOLDER -> BookmarkRow(
                guid = node.guid,
                title = node.title?.takeIf { it.isNotBlank() } ?: return null,
                url = null,
                parentGuid = node.parentGuid,
            )

            BookmarkNodeType.ITEM -> {
                val url = node.url ?: return null
                BookmarkRow(
                    guid = node.guid,
                    title = node.title?.takeIf { it.isNotBlank() } ?: url,
                    url = url,
                    parentGuid = node.parentGuid,
                )
            }

            BookmarkNodeType.SEPARATOR -> null
        }

        fun from(nodes: List<BookmarkNode>): List<BookmarkRow> = nodes.mapNotNull { from(it) }
    }
}

/**
 * Rows for the bookmarks screen and for the new tab page.
 *
 * [onOverflow] is null where there is nothing to edit — the new tab page is a
 * place to open bookmarks, not to manage them — and the button is hidden.
 */
class BookmarkListAdapter(
    private val onClick: (BookmarkRow) -> Unit,
    private val onOverflow: ((BookmarkRow, View) -> Unit)? = null,
) : ListAdapter<BookmarkRow, BookmarkViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder =
        BookmarkViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false),
        )

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position), onClick, onOverflow)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BookmarkRow>() {
            override fun areItemsTheSame(oldItem: BookmarkRow, newItem: BookmarkRow) =
                oldItem.guid == newItem.guid

            override fun areContentsTheSame(oldItem: BookmarkRow, newItem: BookmarkRow) =
                oldItem == newItem
        }
    }
}

class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val icon: ImageView = view.findViewById(R.id.bookmarkIcon)
    private val title: TextView = view.findViewById(R.id.bookmarkTitle)
    private val url: TextView = view.findViewById(R.id.bookmarkUrl)
    private val overflow: ImageButton = view.findViewById(R.id.bookmarkOverflow)

    fun bind(
        row: BookmarkRow,
        onClick: (BookmarkRow) -> Unit,
        onOverflow: ((BookmarkRow, View) -> Unit)?,
    ) {
        icon.setImageResource(if (row.isFolder) R.drawable.ic_folder else R.drawable.ic_bookmark)
        title.text = row.title
        url.text = row.url.orEmpty()
        url.visibility = if (row.url.isNullOrEmpty()) View.GONE else View.VISIBLE

        itemView.setOnClickListener { onClick(row) }

        if (onOverflow == null) {
            overflow.visibility = View.GONE
        } else {
            overflow.visibility = View.VISIBLE
            overflow.setOnClickListener { onOverflow(row, it) }
        }
    }
}
