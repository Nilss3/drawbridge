package app.drawbridge.herald.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.drawbridge.herald.BrowserActivity
import app.drawbridge.herald.R
import app.drawbridge.herald.downloads.DownloadService
import app.drawbridge.herald.ext.applySystemBarInsets
import app.drawbridge.herald.ext.requireComponents
import app.drawbridge.herald.tabs.TabsTrayFragment
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.contextmenu.ContextMenuFeature
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.findinpage.view.FindInPageBar
import mozilla.components.feature.findinpage.view.FindInPageView
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode

/** The browsing screen: toolbar, engine view and the features attached to them. */
class BrowserFragment :
    Fragment(),
    UserInteractionHandler,
    ActivityResultHandler {

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarIntegration = ViewBoundFeatureWrapper<ToolbarIntegration>()
    private val contextMenuFeature = ViewBoundFeatureWrapper<ContextMenuFeature>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val findInPageIntegration = ViewBoundFeatureWrapper<FindInPageIntegration>()
    private val readerViewIntegration = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()

    private val backHandlers: List<ViewBoundFeatureWrapper<*>>
        get() = listOf(
            fullScreenFeature,
            findInPageIntegration,
            readerViewIntegration,
            toolbarIntegration,
            sessionFeature,
        )

    private val sessionId: String? get() = arguments?.getString(ARG_SESSION_ID)

    private val engineView: EngineView get() = requireView().findViewById<View>(R.id.engineView) as EngineView
    private val toolbar: BrowserToolbar get() = requireView().findViewById(R.id.toolbar)
    private val findInPageBar: FindInPageBar get() = requireView().findViewById(R.id.findInPageBar)
    private val swipeRefresh: SwipeRefreshLayout get() = requireView().findViewById(R.id.swipeRefresh)
    private val readerViewControls: ReaderViewControlsBar
        get() = requireView().findViewById(R.id.readerViewControls)

    private lateinit var downloadPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var sitePermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var promptPermissionsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloadPermissionsLauncher = registerPermissionLauncher { permissions, results ->
            downloadsFeature.withFeature { it.onPermissionsResult(permissions, results) }
        }
        sitePermissionsLauncher = registerPermissionLauncher { permissions, results ->
            sitePermissionsFeature.withFeature { it.onPermissionsResult(permissions, results) }
        }
        promptPermissionsLauncher = registerPermissionLauncher { permissions, results ->
            promptsFeature.withFeature { it.onPermissionsResult(permissions, results) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    @Suppress("LongMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val components = requireComponents

        // The toolbar sits at the top of this layout, so without the inset it
        // renders underneath the status bar. Padding the root keeps the engine
        // view's own height in sync with it, and the root's background is the
        // toolbar colour, so the padded strips read as part of the chrome.
        view.applySystemBarInsets(top = true, bottom = true, sides = true)

        sessionFeature.set(
            feature = SessionFeature(
                components.core.store,
                components.useCases.sessionUseCases.goBack,
                components.useCases.sessionUseCases.goForward,
                engineView,
                sessionId,
            ),
            owner = this,
            view = view,
        )

        toolbarIntegration.set(
            feature = ToolbarIntegration(
                context = requireContext(),
                toolbar = toolbar,
                historyStorage = components.core.historyStorage,
                bookmarksStorage = components.core.bookmarksStorage,
                store = components.core.store,
                sessionUseCases = components.useCases.sessionUseCases,
                tabsUseCases = components.useCases.tabsUseCases,
                sessionId = sessionId,
            ),
            owner = this,
            view = view,
        )

        TabsToolbarFeature(
            toolbar = toolbar,
            store = components.core.store,
            sessionId = sessionId,
            lifecycleOwner = viewLifecycleOwner,
            showTabs = ::showTabs,
            countBasedOnSelectedTabType = false,
        )

        contextMenuFeature.set(
            feature = ContextMenuFeature(
                fragmentManager = parentFragmentManager,
                store = components.core.store,
                candidates = ContextMenuCandidate.defaultCandidates(
                    context = requireContext(),
                    tabsUseCases = components.useCases.tabsUseCases,
                    contextMenuUseCases = components.useCases.contextMenuUseCases,
                    snackBarParentView = view,
                    downloadsLocation = components.downloads.location,
                ),
                engineView = engineView,
                useCases = components.useCases.contextMenuUseCases,
                tabId = sessionId,
            ),
            owner = this,
            view = view,
        )

        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext().applicationContext,
                store = components.core.store,
                useCases = components.useCases.downloadsUseCases,
                fragmentManager = childFragmentManager,
                downloadFileUtils = components.downloads.fileUtils,
                downloadManager = FetchDownloadManager(
                    requireContext().applicationContext,
                    components.core.store,
                    DownloadService::class,
                    notificationsDelegate = components.notificationsDelegate,
                ),
                onNeedToRequestPermissions = { downloadPermissionsLauncher.launch(it) },
            ),
            owner = this,
            view = view,
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                requireContext(),
                store = components.core.store,
                sessionId = sessionId,
                fragmentManager = parentFragmentManager,
                // Handing a link to another app would step outside the filter, so
                // this only ever applies to non-web schemes (tel:, mailto:), and
                // then only with a confirmation prompt.
                launchInApp = { false },
            ),
            owner = this,
            view = view,
        )

        promptsFeature.set(
            feature = PromptFeature(
                fragment = this,
                store = components.core.store,
                tabsUseCases = components.useCases.tabsUseCases,
                customTabId = sessionId,
                fragmentManager = parentFragmentManager,
                fileUploadsDirCleaner = components.core.fileUploadsDirCleaner,
                loginValidationDelegate = components.core.loginValidationDelegate,
                isLoginAutofillEnabled = { true },
                isSaveLoginEnabled = { true },
                loginExceptionStorage = components.core.loginExceptionStorage,
                onNeedToRequestPermissions = { promptPermissionsLauncher.launch(it) },
            ),
            owner = this,
            view = view,
        )

        windowFeature.set(
            feature = WindowFeature(components.core.store, components.useCases.tabsUseCases),
            owner = this,
            view = view,
        )

        // Captures the screenshot the tab grid shows. ThumbnailsMiddleware only
        // stores them; without this nothing ever takes one and every card in the
        // grid is blank.
        thumbnailsFeature.set(
            feature = BrowserThumbnails(requireContext(), engineView, components.core.store),
            owner = this,
            view = view,
        )

        fullScreenFeature.set(
            feature = FullScreenFeature(
                store = components.core.store,
                sessionUseCases = components.useCases.sessionUseCases,
                tabId = sessionId,
                viewportFitChanged = ::viewportFitChanged,
                fullScreenChanged = ::fullScreenChanged,
            ),
            owner = this,
            view = view,
        )

        findInPageIntegration.set(
            feature = FindInPageIntegration(
                components.core.store,
                sessionId,
                findInPageBar as FindInPageView,
                engineView,
            ),
            owner = this,
            view = view,
        )

        readerViewIntegration.set(
            feature = ReaderViewIntegration(
                context = requireContext(),
                engine = components.core.engine,
                store = components.core.store,
                sessionId = sessionId,
                controlsView = readerViewControls,
            ),
            owner = this,
            view = view,
        )

        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                sessionId = sessionId,
                storage = components.core.geckoSitePermissionsStorage,
                onNeedToRequestPermissions = { sitePermissionsLauncher.launch(it) },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = components.core.store,
            ),
            owner = this,
            view = view,
        )

        // No EngineViewClippingBehavior: that exists to slide a dynamic toolbar
        // out of the way, and herald's toolbar is fixed. It positions the engine
        // view itself, ignoring the root's padding, which pushed the bottom of
        // every page under the navigation bar. A plain top margin leaves the
        // engine view inside the padded area, where the insets already put it.
        setEngineViewTopMargin(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                components.core.store,
                components.useCases.sessionUseCases.reload,
                swipeRefresh,
            ),
            owner = this,
            view = view,
        )

        if (components.core.store.state.selectedTab == null) {
            components.useCases.tabsUseCases.addTab(
                url = "about:blank",
                selectTab = true,
            )
        }
    }

    private fun showTabs() {
        (activity as? BrowserActivity)?.showFragment(TabsTrayFragment())
    }

    private fun fullScreenChanged(enabled: Boolean) {
        if (enabled) {
            activity?.enterImmersiveMode()
            toolbar.visibility = View.GONE
            // The system bars are hidden, so their insets go to zero on their
            // own; the toolbar's own space has to be given back by hand.
            setEngineViewTopMargin(0)
        } else {
            activity?.exitImmersiveMode()
            toolbar.visibility = View.VISIBLE
            setEngineViewTopMargin(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
        }
    }

    private fun setEngineViewTopMargin(margin: Int) {
        val params = swipeRefresh.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        params.topMargin = margin
        swipeRefresh.layoutParams = params
    }

    private fun viewportFitChanged(viewportFit: Int) {
        requireActivity().window.attributes.layoutInDisplayCutoutMode = viewportFit
    }

    override fun onBackPressed(): Boolean = backHandlers.any { it.onBackPressed() }

    override fun onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean =
        promptsFeature.get()?.onActivityResult(requestCode, data, resultCode) ?: false

    private fun registerPermissionLauncher(
        onResult: (Array<String>, IntArray) -> Unit,
    ): ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val permissions = results.keys.toTypedArray()
            val grants = results.values.map {
                if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }.toIntArray()
            onResult(permissions, grants)
        }

    companion object {
        private const val ARG_SESSION_ID = "session_id"

        fun create(sessionId: String? = null): BrowserFragment = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARG_SESSION_ID, sessionId) }
        }
    }
}
