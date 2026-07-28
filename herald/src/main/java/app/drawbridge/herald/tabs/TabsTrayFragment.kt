package app.drawbridge.herald.tabs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.drawbridge.herald.BrowserActivity
import app.drawbridge.herald.R
import app.drawbridge.herald.browser.BrowserFragment
import app.drawbridge.herald.ext.applySystemBarInsets
import app.drawbridge.herald.ext.requireComponents
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.tabstray.TabsAdapter
import mozilla.components.browser.tabstray.TabsTray
import mozilla.components.browser.tabstray.TabsTrayStyling
import mozilla.components.browser.thumbnails.loader.ThumbnailLoader
import mozilla.components.feature.tabs.tabstray.TabsFeature
import mozilla.components.support.base.feature.UserInteractionHandler

/** Lists the open tabs and lets the user switch between, add or close them. */
class TabsTrayFragment : Fragment(), UserInteractionHandler {

    private var tabsFeature: TabsFeature? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tabstray, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.applySystemBarInsets(top = true, bottom = true, sides = true)

        val tray = createTabsTray(view)
        tabsFeature = TabsFeature(tray, requireComponents.core.store, ::closeTray)

        view.findViewById<View>(R.id.newTabButton).setOnClickListener {
            requireComponents.useCases.tabsUseCases.addTab("about:blank", selectTab = true)
            closeTray()
        }

        view.findViewById<View>(R.id.closeAllTabsButton).setOnClickListener {
            requireComponents.useCases.tabsUseCases.removeAllTabs()
            requireComponents.useCases.tabsUseCases.addTab("about:blank", selectTab = true)
            closeTray()
        }

        view.findViewById<View>(R.id.doneButton).setOnClickListener { closeTray() }
    }

    override fun onStart() {
        super.onStart()
        tabsFeature?.start()
    }

    override fun onStop() {
        tabsFeature?.stop()
        super.onStop()
    }

    override fun onBackPressed(): Boolean {
        closeTray()
        return true
    }

    private fun closeTray() {
        (activity as? BrowserActivity)?.showFragment(BrowserFragment.create())
    }

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private fun createTabsTray(view: View): TabsTray {
        val thumbnailLoader = ThumbnailLoader(requireComponents.core.thumbnailStorage)

        val adapter = TabsAdapter(
            thumbnailLoader = thumbnailLoader,
            // Without this the rows keep the library's white card and bright blue
            // selection, whatever the rest of the app is doing.
            styling = TabsTrayStyling(
                itemBackgroundColor = color(R.color.tab_item_background),
                selectedItemBackgroundColor = color(R.color.tab_item_selected_background),
                itemTextColor = color(R.color.tab_item_text),
                selectedItemTextColor = color(R.color.tab_item_text),
                itemUrlTextColor = color(R.color.tab_item_url),
                selectedItemUrlTextColor = color(R.color.tab_item_url),
            ),
            delegate = object : TabsTray.Delegate {
                override fun onTabSelected(tab: TabSessionState, source: String?) {
                    requireComponents.useCases.tabsUseCases.selectTab(tab.id)
                    closeTray()
                }

                override fun onTabClosed(tab: TabSessionState, source: String?) {
                    requireComponents.useCases.tabsUseCases.removeTab(tab.id)
                }
            },
        )

        view.findViewById<RecyclerView>(R.id.tabsTray).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        return adapter
    }
}
