package app.drawbridge.herald.components

import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.SettingsUseCases
import mozilla.components.feature.tabs.TabsUseCases

class UseCases(
    engine: Engine,
    store: BrowserStore,
    downloads: Downloads,
) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val searchUseCases by lazy { SearchUseCases(store, tabsUseCases, sessionUseCases) }
    val settingsUseCases by lazy { SettingsUseCases(engine, store) }
    val contextMenuUseCases by lazy { ContextMenuUseCases(store) }
    val downloadsUseCases by lazy { DownloadsUseCases(store, downloads.fileUtils) }
}
