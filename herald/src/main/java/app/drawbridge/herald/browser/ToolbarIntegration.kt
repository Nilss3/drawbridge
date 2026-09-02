package app.drawbridge.herald.browser

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import app.drawbridge.herald.Edition
import app.drawbridge.herald.R
import app.drawbridge.herald.bookmarks.BookmarksActivity
import app.drawbridge.herald.ext.components
import app.drawbridge.herald.ext.share
import app.drawbridge.herald.history.HistoryActivity
import app.drawbridge.herald.logins.LoginsActivity
import app.drawbridge.herald.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
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
import mozilla.components.support.ktx.android.view.hideKeyboard
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
    /**
     * Closes every tab and leaves, for the last entry in the menu.
     *
     * Passed in rather than done here for the same reason [EditSafeToolbar]
     * takes its keyboard dismissal: the leaving half needs an Activity, which
     * this class does not have and should not acquire by casting its Context.
     * It is one lambda rather than two halves because the order matters — the
     * tabs have to go before the task does, or the next launch restores them.
     */
    private val quit: () -> Unit = {},
) : LifecycleAwareFeature, UserInteractionHandler {

    private val scope = MainScope()

    private val shippedDomainsProvider = ShippedDomainsProvider().also { it.initialize(context) }

    /** The selected tab's URL when it is already bookmarked; null otherwise. */
    private var bookmarkedUrl: String? = null

    private val menuController: MenuController = BrowserMenuController()

    private val iconTint by lazy { ContextCompat.getColor(context, R.color.menu_icon) }

    /**
     * Reader view, in the address bar rather than only in the menu.
     *
     * It appears exactly when Gecko says the page has an article in it, which
     * is the same signal the menu entry has always used — so the button is not
     * a new capability, it is the existing one where the eye already is. The
     * menu entry stays: it is the only place the state is *named*, and a page
     * action that has vanished because a page turned out not to be readerable
     * cannot explain itself.
     *
     * `selected` is a field on the button rather than a lambda, so the
     * collector below has to push reader state into it; `false` on the second
     * argument stops that push being mistaken for a tap.
     */
    private val readerButton: BrowserToolbar.ToggleButton by lazy {
        BrowserToolbar.ToggleButton(
            image = tinted(mozilla.components.ui.icons.R.drawable.mozac_ic_reader_view_24),
            imageSelected = tinted(mozilla.components.ui.icons.R.drawable.mozac_ic_reader_view_fill_24),
            contentDescription = context.getString(R.string.menu_reader_view),
            contentDescriptionSelected = context.getString(R.string.reader_view_off),
            visible = {
                val reader = store.state.selectedTab?.readerState
                reader?.readerable == true || reader?.active == true
            },
        ) { ReaderViewIntegration.toggle?.invoke() }
    }

    /**
     * A leading icon for a menu entry.
     *
     * Every entry has one and none is decorative: a menu where some rows are
     * indented by an icon and others are not reads as broken, and the eye uses
     * the icon column to find the row it wants without reading any of them.
     */
    private fun icon(resId: Int) = DrawableMenuIcon(context, resId, tint = iconTint)

    private fun tinted(resId: Int): Drawable =
        AppCompatResources.getDrawable(context, resId)!!.mutate().also {
            DrawableCompat.setTint(it, iconTint)
        }

    /**
     * The presenter writes into the toolbar on every state update, including
     * into the field being typed in. [EditSafeToolbar] is what stops that; see
     * its KDoc. Only this feature is wrapped — everything else here talks to the
     * real toolbar.
     */
    private val toolbarFeature = ToolbarFeature(
        EditSafeToolbar(toolbar) { toolbar.hideKeyboard() },
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
            // Only the security indicator, which actually varies: https, plain
            // http, or the data: URL the block page loads from. The tracking
            // protection shield is gone — the policy is on for every page and
            // cannot be changed, and tapping it opened nothing, so it was a fixed
            // ornament taking up the left of the toolbar.
            indicators = listOf(DisplayToolbar.Indicators.SECURITY)
            displayIndicatorSeparator = false
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

        toolbar.addPageAction(readerButton)

        scope.launch {
            store.flow()
                .map { it.selectedTab }
                .distinctUntilChanged()
                .collect { tab ->
                    menuController.submitList(menuItems(tab))
                    // The button's own `visible` lambda is only consulted when
                    // the toolbar re-reads its actions, so a page becoming
                    // readerable has to say so.
                    readerButton.setSelected(tab?.readerState?.active == true, false)
                    toolbar.invalidateActions()
                }
        }

        // Whether the page is already bookmarked decides one menu entry, and
        // answering it means asking storage. Kept on its own collector keyed to
        // the URL, so it runs once per page rather than on every progress and
        // title update the tab emits while loading.
        scope.launch {
            store.flow()
                .map { it.selectedTab?.content?.url }
                .distinctUntilChanged()
                .collect { url -> refreshBookmarkedUrl(url) }
        }
    }

    private suspend fun refreshBookmarkedUrl(url: String?) {
        bookmarkedUrl = url?.takeIf {
            withContext(Dispatchers.IO) {
                bookmarksStorage.getBookmarksWithUrl(it).getOrNull().orEmpty().isNotEmpty()
            }
        }
        menuController.submitList(menuItems(store.state.selectedTab))
    }

    override fun start() = toolbarFeature.start()

    override fun stop() = toolbarFeature.stop()

    override fun onBackPressed(): Boolean = toolbarFeature.onBackPressed()

    fun destroy() {
        scope.cancel()
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
        // The navigation row is a strip of icons rather than an entry, so "New
        // tab" directly under it is the top of the menu proper — where Chrome
        // and Firefox both put it. Mono has neither: one page at a time is the
        // whole point of it.
        val head = listOfNotNull(
            session?.let { navigationRow(it) },
            TextMenuCandidate(
                context.getString(R.string.menu_new_tab),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_plus_24),
            ) {
                tabsUseCases.addTab.invoke("about:blank", selectTab = true)
            }.takeIf { Edition.hasTabs },
        )

        val sessionItems = if (session == null) {
            emptyList()
        } else {
            listOfNotNull(
                // "Edit" rather than a second "Add" once the page is already
                // bookmarked: adding twice made a duplicate with no way to see
                // it had happened.
                if (session.content.url == bookmarkedUrl) {
                    TextMenuCandidate(
                        context.getString(R.string.bookmark_edit),
                        start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_fill_24),
                    ) {
                        context.startActivity(
                            BookmarksActivity.editIntent(context, session.content.url),
                        )
                    }
                } else {
                    TextMenuCandidate(
                        context.getString(R.string.menu_add_bookmark),
                        start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_24),
                    ) {
                        addBookmark(session.content.title, session.content.url)
                    }
                },
                TextMenuCandidate(
                    context.getString(R.string.menu_share),
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_share_android_24),
                ) {
                    context.share(session.content.url)
                },
                TextMenuCandidate(
                    context.getString(R.string.menu_find_in_page),
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_search_24),
                ) {
                    FindInPageIntegration.launch?.invoke()
                },
                CompoundMenuCandidate(
                    text = context.getString(R.string.menu_desktop_site),
                    isChecked = session.content.desktopMode,
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_device_desktop_24),
                    end = CompoundMenuCandidate.ButtonType.SWITCH,
                ) { checked -> sessionUseCases.requestDesktopSite.invoke(checked) },
                // Only offered where there is an article to strip down to;
                // Gecko decides that per page and reports it in readerState.
                CompoundMenuCandidate(
                    text = context.getString(R.string.menu_reader_view),
                    isChecked = session.readerState.active,
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_reader_view_24),
                    end = CompoundMenuCandidate.ButtonType.SWITCH,
                ) { ReaderViewIntegration.toggle?.invoke() }
                    .takeIf { session.readerState.readerable },
                TextMenuCandidate(
                    context.getString(R.string.menu_reader_view_options),
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_reader_view_customize_24),
                ) {
                    ReaderViewIntegration.showControls?.invoke()
                }.takeIf { session.readerState.active },
                // Mono only, and only worth offering while the page is still
                // grey: colour lasts until you navigate away, so an entry that
                // said "show in colour" on an already-coloured page would be
                // offering to do nothing.
                TextMenuCandidate(
                    context.getString(R.string.menu_show_colour),
                    start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_image_24),
                ) {
                    GreyscaleIntegration.current?.restoreColour()
                }.takeIf { Edition.greyscale && GreyscaleIntegration.current?.isColourRestored == false },
            )
        }

        return head + sessionItems + listOf(
            TextMenuCandidate(
                context.getString(R.string.menu_bookmarks),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_tray_24),
            ) {
                context.startActivityNewTask(BookmarksActivity::class.java)
            },
            TextMenuCandidate(
                context.getString(R.string.menu_history),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_history_24),
            ) {
                context.startActivityNewTask(HistoryActivity::class.java)
            },
            TextMenuCandidate(
                context.getString(R.string.menu_passwords),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_login_24),
            ) {
                context.startActivityNewTask(LoginsActivity::class.java)
            },
            // The ad blocker's own dashboard used to sit here. It is a settings
            // screen, so it lives in settings; see SettingsFragment.
            TextMenuCandidate(
                context.getString(R.string.menu_settings),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_settings_24),
            ) {
                context.startActivityNewTask(SettingsActivity::class.java)
            },
            // Last, and the only way out that actually ends the process's work:
            // the back press deliberately does moveTaskToBack instead, to keep
            // the engine warm.
            TextMenuCandidate(
                context.getString(R.string.menu_quit),
                start = icon(mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24),
            ) { quit() },
        )
    }

    private fun addBookmark(title: String, url: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                bookmarksStorage.addItem(
                    parentGuid = BookmarkRoot.Mobile.id,
                    url = url,
                    title = title.ifEmpty { url },
                    position = null,
                )
            }
            Toast.makeText(context, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
            // So the entry becomes "Edit bookmark" without waiting for a
            // navigation to re-trigger the lookup.
            refreshBookmarkedUrl(url)
        }
    }

    private fun Context.startActivityNewTask(target: Class<*>) {
        startActivity(Intent(this, target).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }
}
