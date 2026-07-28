package app.drawbridge.herald.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.drawbridge.herald.R
import app.drawbridge.herald.bookmarks.BookmarksActivity
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.ext.share
import app.drawbridge.herald.history.HistoryActivity
import app.drawbridge.herald.logins.LoginsActivity
import app.drawbridge.herald.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider
import mozilla.components.browser.menu2.BrowserMenuController
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.concept.menu.MenuController
import mozilla.components.concept.menu.candidate.CompoundMenuCandidate
import mozilla.components.concept.menu.candidate.ContainerStyle
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.MenuCandidate
import mozilla.components.concept.menu.candidate.RowMenuCandidate
import mozilla.components.concept.menu.candidate.SmallMenuCandidate
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.feature.toolbar.ToolbarAutocompleteFeature
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/**
 * The address bar: URL display and editing, inline autocomplete, and the app
 * menu.
 *
 * Autocomplete is fed from local history and a shipped list of popular domains.
 * There is deliberately no search-suggestion dropdown — suggestions come from
 * the search provider and would surface exactly the terms this browser exists to
 * keep off the screen.
 */
class ToolbarIntegration(
    private val context: Context,
    private val toolbar: BrowserToolbar,
    historyStorage: PlacesHistoryStorage,
    private val bookmarksStorage: PlacesBookmarksStorage,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    sessionId: String? = null,
) : LifecycleAwareFeature, UserInteractionHandler {

    private val scope = MainScope()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val shippedDomainsProvider = ShippedDomainsProvider().also { it.initialize(context) }

    private val menuController: MenuController = BrowserMenuController()

    private val toolbarFeature = ToolbarFeature(
        toolbar,
        store,
        sessionUseCases.loadUrl,
        { searchTerms -> context.components.useCases.searchUseCases.defaultSearch.invoke(searchTerms) },
        sessionId,
    )

    init {
        val foreground = ContextCompat.getColor(context, R.color.toolbar_text)
        val hintColor = ContextCompat.getColor(context, R.color.toolbar_hint)
        val menuColor = ContextCompat.getColor(context, R.color.menu_icon)

        toolbar.display.apply {
            indicators = listOf(
                DisplayToolbar.Indicators.SECURITY,
                DisplayToolbar.Indicators.TRACKING_PROTECTION,
            )
            displayIndicatorSeparator = true
            menuController = this@ToolbarIntegration.menuController
            hint = context.getString(R.string.toolbar_hint)
            // Every foreground in the toolbar is set from the chrome palette.
            // The library's defaults assume a light toolbar, so anything left
            // unset here is invisible in night mode.
            colors = colors.copy(
                siteInfoIconSecure = foreground,
                siteInfoIconInsecure = foreground,
                siteInfoIconLocalPdf = foreground,
                emptyIcon = foreground,
                menu = menuColor,
                hint = hintColor,
                title = foreground,
                text = foreground,
                separator = hintColor,
            )
        }

        toolbar.edit.apply {
            hint = context.getString(R.string.toolbar_hint)
            // Editing the URL was the other half of that: without this the entry
            // field keeps the library's dark-on-light default and the text is
            // unreadable against the toolbar.
            colors = colors.copy(
                clear = foreground,
                icon = foreground,
                hint = hintColor,
                text = foreground,
            )
        }

        ToolbarAutocompleteFeature(toolbar).apply {
            updateAutocompleteProviders(listOf(historyStorage, shippedDomainsProvider))
        }

        scope.launch {
            store.flow()
                .map { it.selectedTab }
                .distinctUntilChanged()
                .collect { menuController.submitList(menuItems(it)) }
        }
    }

    override fun start() = toolbarFeature.start()

    override fun stop() = toolbarFeature.stop()

    override fun onBackPressed(): Boolean = toolbarFeature.onBackPressed()

    fun destroy() {
        scope.cancel()
        ioScope.cancel()
    }

    private fun navigationRow(session: TabSessionState?): RowMenuCandidate {
        val tint = ContextCompat.getColor(context, R.color.menu_icon)

        val forward = SmallMenuCandidate(
            contentDescription = context.getString(R.string.menu_forward),
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_forward_24,
                tint = tint,
            ),
            containerStyle = ContainerStyle(isEnabled = session?.content?.canGoForward == true),
        ) { sessionUseCases.goForward.invoke() }

        val refresh = SmallMenuCandidate(
            contentDescription = context.getString(R.string.menu_refresh),
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                tint = tint,
            ),
        ) { sessionUseCases.reload.invoke() }

        val stop = SmallMenuCandidate(
            contentDescription = context.getString(R.string.menu_stop),
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24,
                tint = tint,
            ),
        ) { sessionUseCases.stopLoading.invoke() }

        return RowMenuCandidate(listOf(forward, refresh, stop))
    }

    private fun menuItems(session: TabSessionState?): List<MenuCandidate> {
        val sessionItems = if (session == null) {
            emptyList()
        } else {
            listOfNotNull(
                navigationRow(session),
                TextMenuCandidate(context.getString(R.string.menu_add_bookmark)) {
                    addBookmark(session.content.title, session.content.url)
                },
                TextMenuCandidate(context.getString(R.string.menu_share)) {
                    context.share(session.content.url)
                },
                TextMenuCandidate(context.getString(R.string.menu_find_in_page)) {
                    FindInPageIntegration.launch?.invoke()
                },
                CompoundMenuCandidate(
                    text = context.getString(R.string.menu_desktop_site),
                    isChecked = session.content.desktopMode,
                    end = CompoundMenuCandidate.ButtonType.SWITCH,
                ) { checked -> sessionUseCases.requestDesktopSite.invoke(checked) },
                // Only offered where there is an article to strip down to;
                // Gecko decides that per page and reports it in readerState.
                CompoundMenuCandidate(
                    text = context.getString(R.string.menu_reader_view),
                    isChecked = session.readerState.active,
                    end = CompoundMenuCandidate.ButtonType.SWITCH,
                ) { ReaderViewIntegration.toggle?.invoke() }
                    .takeIf { session.readerState.readerable },
                TextMenuCandidate(context.getString(R.string.menu_reader_view_options)) {
                    ReaderViewIntegration.showControls?.invoke()
                }.takeIf { session.readerState.active },
            )
        }

        return sessionItems + listOf(
            TextMenuCandidate(context.getString(R.string.menu_new_tab)) {
                tabsUseCases.addTab.invoke("about:blank", selectTab = true)
            },
            TextMenuCandidate(context.getString(R.string.menu_bookmarks)) {
                context.startActivityNewTask(BookmarksActivity::class.java)
            },
            TextMenuCandidate(context.getString(R.string.menu_history)) {
                context.startActivityNewTask(HistoryActivity::class.java)
            },
            TextMenuCandidate(context.getString(R.string.menu_passwords)) {
                context.startActivityNewTask(LoginsActivity::class.java)
            },
            TextMenuCandidate(context.getString(R.string.menu_settings)) {
                context.startActivityNewTask(SettingsActivity::class.java)
            },
        )
    }

    private fun addBookmark(title: String, url: String) {
        ioScope.launch {
            bookmarksStorage.addItem(
                parentGuid = mozilla.appservices.places.BookmarkRoot.Mobile.id,
                url = url,
                title = title.ifEmpty { url },
                position = null,
            )
        }
    }

    private fun Context.startActivityNewTask(target: Class<*>) {
        startActivity(Intent(this, target).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }
}
