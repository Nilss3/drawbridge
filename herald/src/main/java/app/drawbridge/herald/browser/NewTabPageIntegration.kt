package app.drawbridge.herald.browser

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.drawbridge.herald.bookmarks.BookmarkListAdapter
import app.drawbridge.herald.bookmarks.BookmarkRepository
import app.drawbridge.herald.bookmarks.BookmarkRow
import app.drawbridge.herald.settings.HeraldSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/**
 * The bookmark list shown on a blank tab.
 *
 * Off by default; [HeraldSettings.KEY_NEW_TAB_BOOKMARKS] turns it on. The
 * setting is read each time the feature starts, so coming back from settings
 * applies it without restarting the browser.
 *
 * Folders can be opened in place and back steps out of them, which is why this
 * is a [UserInteractionHandler]: the alternative is a back press inside a folder
 * leaving the browser.
 */
class NewTabPageIntegration(
    private val context: Context,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val repository: BookmarkRepository,
    private val overlay: View,
    list: RecyclerView,
    private val emptyView: TextView,
    private val sessionId: String? = null,
) : LifecycleAwareFeature, UserInteractionHandler {

    private var scope: CoroutineScope? = null

    /** Guids from the root down to the folder on screen; never empty. */
    private val path = ArrayDeque<String>()

    private var enabled = false

    private val adapter = BookmarkListAdapter(onClick = ::onRowClicked)

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
    }

    override fun start() {
        enabled = HeraldSettings.showBookmarksOnNewTab(context)
        path.clear()
        path.addLast(repository.rootGuid)

        if (!enabled) {
            overlay.visibility = View.GONE
            return
        }

        scope = MainScope().also { scope ->
            scope.launch {
                store.flow()
                    .map { it.findTabOrCustomTabOrSelectedTab(sessionId)?.content?.url }
                    .distinctUntilChanged()
                    .collect { url -> onUrlChanged(url) }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }

    override fun onBackPressed(): Boolean {
        if (!isShowing() || path.size <= 1) return false
        path.removeLast()
        scope?.launch { render() }
        return true
    }

    private fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    private suspend fun onUrlChanged(url: String?) {
        if (url == BLANK_URL) {
            // A new tab always starts at the root; a folder left open on the
            // last blank tab is not where the next one should begin.
            path.clear()
            path.addLast(repository.rootGuid)
            overlay.visibility = View.VISIBLE
            render()
        } else {
            overlay.visibility = View.GONE
        }
    }

    private suspend fun render() {
        val rows = BookmarkRow.from(repository.children(path.last()))
        adapter.submitList(rows)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onRowClicked(row: BookmarkRow) {
        if (row.isFolder) {
            path.addLast(row.guid)
            scope?.launch { render() }
        } else {
            row.url?.let { sessionUseCases.loadUrl(it, sessionId) }
        }
    }

    private companion object {
        const val BLANK_URL = "about:blank"
    }
}
