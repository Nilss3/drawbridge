package app.drawbridge.herald.browser

import app.drawbridge.herald.Edition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow

/**
 * Holds mono to exactly one tab.
 *
 * Taking the tab bar and the tray away removes the *sight* of tabs, not the
 * ability to make them: an intent from another app, a `window.open` that got
 * past the engine, a restored session from an older build — each still adds
 * one, and in a browser with no tray those pile up invisibly, holding pages and
 * memory nobody can see or close.
 *
 * Enforcing the invariant is better than finding every path to it. Whatever
 * creates a tab, the selected one survives and the rest are closed, so a link
 * arriving from another app still does what the user meant — it replaces the
 * page — and nothing accumulates behind it.
 *
 * The selected tab is the one kept because every path that adds a tab also
 * selects it: what the user just asked for is what stays.
 */
fun enforceSingleTab(
    scope: CoroutineScope,
    store: BrowserStore,
    tabsUseCases: TabsUseCases,
) {
    if (Edition.hasTabs) return

    scope.launch {
        store.flow()
            .map { state -> state.tabs.map { it.id } to state.selectedTabId }
            .distinctUntilChanged()
            .collect { (ids, selectedId) ->
                if (ids.size <= 1) return@collect
                val keep = selectedId?.takeIf { it in ids } ?: ids.last()
                ids.filterNot { it == keep }.forEach { tabsUseCases.removeTab(it) }
            }
    }
}
