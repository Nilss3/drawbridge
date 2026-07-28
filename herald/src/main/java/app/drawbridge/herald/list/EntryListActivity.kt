package app.drawbridge.herald.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.applySystemBarInsets
import kotlinx.coroutines.launch

/**
 * Shared screen for history, bookmarks and saved passwords: a titled list where
 * each row can be opened and removed.
 *
 * The three screens differ only in where their rows come from, so they share one
 * activity rather than three near-identical ones.
 */
abstract class EntryListActivity : AppCompatActivity() {

    /** A single row. [secondary] is shown under [primary] when present. */
    data class Entry(
        val id: String,
        val primary: String,
        val secondary: String? = null,
    )

    protected abstract val titleResId: Int
    protected abstract val emptyMessageResId: Int

    /** Loads the rows to display. Called on a background dispatcher. */
    protected abstract suspend fun loadEntries(): List<Entry>

    /** Invoked when a row is tapped. Default: do nothing. */
    protected open fun onEntryClicked(entry: Entry) = Unit

    /** Invoked when a row's delete button is tapped. */
    protected abstract suspend fun deleteEntry(entry: Entry)

    /** Invoked by the "clear all" action in the app bar. */
    protected abstract suspend fun deleteAll()

    private lateinit var adapter: EntryAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_list)

        findViewById<View>(R.id.root).applySystemBarInsets(top = true, bottom = true, sides = true)

        findViewById<Toolbar>(R.id.toolbar).apply {
            setTitle(titleResId)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_entry_list)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_clear_all) {
                    lifecycleScope.launch {
                        deleteAll()
                        refresh()
                    }
                    true
                } else {
                    false
                }
            }
        }

        emptyView = findViewById<TextView>(R.id.emptyView).apply { setText(emptyMessageResId) }

        adapter = EntryAdapter(
            onClick = { entry ->
                onEntryClicked(entry)
            },
            onDelete = { entry ->
                lifecycleScope.launch {
                    deleteEntry(entry)
                    refresh()
                }
            },
        )

        findViewById<RecyclerView>(R.id.entryList).apply {
            layoutManager = LinearLayoutManager(this@EntryListActivity)
            adapter = this@EntryListActivity.adapter
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val entries = loadEntries()
        adapter.submitList(entries)
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }
}

private class EntryAdapter(
    private val onClick: (EntryListActivity.Entry) -> Unit,
    private val onDelete: (EntryListActivity.Entry) -> Unit,
) : ListAdapter<EntryListActivity.Entry, EntryViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder =
        EntryViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false),
        )

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position), onClick, onDelete)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<EntryListActivity.Entry>() {
            override fun areItemsTheSame(
                oldItem: EntryListActivity.Entry,
                newItem: EntryListActivity.Entry,
            ) = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: EntryListActivity.Entry,
                newItem: EntryListActivity.Entry,
            ) = oldItem == newItem
        }
    }
}

private class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val primary: TextView = view.findViewById(R.id.entryPrimary)
    private val secondary: TextView = view.findViewById(R.id.entrySecondary)
    private val delete: ImageButton = view.findViewById(R.id.entryDelete)

    fun bind(
        entry: EntryListActivity.Entry,
        onClick: (EntryListActivity.Entry) -> Unit,
        onDelete: (EntryListActivity.Entry) -> Unit,
    ) {
        primary.text = entry.primary
        secondary.text = entry.secondary.orEmpty()
        secondary.visibility = if (entry.secondary.isNullOrEmpty()) View.GONE else View.VISIBLE
        itemView.setOnClickListener { onClick(entry) }
        delete.setOnClickListener { onDelete(entry) }
    }
}
