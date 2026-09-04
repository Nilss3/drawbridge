package app.drawbridge.herald.bookmarks

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
    /** Enters selection mode. Null on the new tab page, which manages nothing. */
    private val onLongClick: ((BookmarkRow) -> Unit)? = null,
    /** Asked to start a drag when the handle is touched. */
    private val onDragHandleTouched: ((BookmarkViewHolder) -> Unit)? = null,
) : ListAdapter<BookmarkRow, BookmarkViewHolder>(DIFF) {

    /**
     * The guids ticked in selection mode, or null when there is no selection
     * mode running.
     *
     * Null and empty are deliberately different: empty means the mode is on and
     * nothing is ticked, which still shows checkboxes and still hides the
     * overflow. Collapsing the two would make the last untick look like an exit.
     */
    var selection: Set<String>? = null
        set(value) {
            val was = field
            field = value
            // Every row changes appearance when the mode opens or closes.
            if ((was == null) != (value == null)) notifyDataSetChanged() else notifyItemRangeChanged(0, itemCount)
        }

    /** True while rows may be dragged: the manager's own folders, unsearched. */
    var arrangeable: Boolean = false
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder =
        BookmarkViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false),
        )

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(
            row = getItem(position),
            onClick = onClick,
            onOverflow = onOverflow,
            onLongClick = onLongClick,
            onDragHandleTouched = onDragHandleTouched,
            selection = selection,
            arrangeable = arrangeable,
        )
    }

    /** The row at [position], for the drag callback. */
    fun rowAt(position: Int): BookmarkRow = getItem(position)

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
    private val check: CheckBox = view.findViewById(R.id.bookmarkCheck)
    private val icon: ImageView = view.findViewById(R.id.bookmarkIcon)
    private val title: TextView = view.findViewById(R.id.bookmarkTitle)
    private val url: TextView = view.findViewById(R.id.bookmarkUrl)
    private val dragHandle: ImageView = view.findViewById(R.id.bookmarkDragHandle)
    private val overflow: ImageButton = view.findViewById(R.id.bookmarkOverflow)

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        row: BookmarkRow,
        onClick: (BookmarkRow) -> Unit,
        onOverflow: ((BookmarkRow, View) -> Unit)?,
        onLongClick: ((BookmarkRow) -> Unit)?,
        onDragHandleTouched: ((BookmarkViewHolder) -> Unit)?,
        selection: Set<String>?,
        arrangeable: Boolean,
    ) {
        icon.setImageResource(if (row.isFolder) R.drawable.ic_folder else R.drawable.ic_bookmark)
        title.text = row.title
        url.text = row.url.orEmpty()
        url.visibility = if (row.url.isNullOrEmpty()) View.GONE else View.VISIBLE

        val selecting = selection != null
        check.visibility = if (selecting) View.VISIBLE else View.GONE
        check.isChecked = selection?.contains(row.guid) == true
        itemView.isActivated = check.isChecked

        // One action at a time. In selection mode a tap ticks rather than
        // opens, because a list you are picking from is not one you are
        // navigating, and half-doing both is how a folder gets opened when
        // somebody meant to select it.
        itemView.setOnClickListener { onClick(row) }
        itemView.setOnLongClickListener(
            onLongClick?.let { handler -> View.OnLongClickListener { handler(row); true } },
        )

        // The handle is the only way to drag: a long press is already spoken
        // for by selection mode, and dragging from anywhere would make an
        // ordinary scroll pick a row up.
        val draggable = arrangeable && !selecting && onDragHandleTouched != null
        dragHandle.visibility = if (draggable) View.VISIBLE else View.GONE
        dragHandle.setOnTouchListener(
            if (!draggable) {
                null
            } else {
                { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onDragHandleTouched?.invoke(this)
                    }
                    false
                }
            },
        )

        if (onOverflow == null || selecting) {
            overflow.visibility = View.GONE
        } else {
            overflow.visibility = View.VISIBLE
            overflow.setOnClickListener { onOverflow(row, it) }
        }
    }
}
