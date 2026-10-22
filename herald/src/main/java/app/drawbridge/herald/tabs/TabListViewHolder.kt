package app.drawbridge.herald.tabs

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.drawbridge.herald.R
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.tabstray.DefaultTabViewHolder
import mozilla.components.browser.tabstray.TabViewHolder
import mozilla.components.browser.tabstray.TabsTray
import mozilla.components.browser.tabstray.TabsTrayStyling
import mozilla.components.concept.base.images.ImageLoader

/**
 * The library's tab row, plus a globe where a tab has no icon to show.
 *
 * In a list every title starts at the same edge only if every row has an icon;
 * a site without one left a gap, and the title beside it looked indented by
 * accident. `DefaultTabViewHolder` is final, so this wraps it rather than
 * extending it and does everything else the library's way.
 */
class TabListViewHolder(view: View, thumbnailLoader: ImageLoader) : TabViewHolder(view) {

    private val inner = DefaultTabViewHolder(view, thumbnailLoader)
    private val icon: ImageView = view.findViewById(R.id.mozac_browser_tabstray_icon)
    private val title: TextView = view.findViewById(R.id.mozac_browser_tabstray_title)
    private val url: TextView = view.findViewById(R.id.mozac_browser_tabstray_url)

    override var tab: TabSessionState?
        get() = inner.tab
        set(value) {
            inner.tab = value
        }

    override fun bind(
        tab: TabSessionState,
        isSelected: Boolean,
        styling: TabsTrayStyling,
        delegate: TabsTray.Delegate,
    ) {
        inner.bind(tab, isSelected, styling, delegate)
        // **A row shows two lines of title and one of address, and TextView still
        // lays out every character it is given.** A page with a multi-megabyte
        // title, or a data: URL, therefore froze the main thread for tens of
        // seconds the moment the tray opened — an ANR on the phone, measured at
        // 40 seconds for a 3 MB title on the emulator. The cut is far past what
        // any row can display, so nothing visible changes.
        title.text = title.text.take(MAX_ROW_TEXT)
        url.text = url.text.take(MAX_ROW_TEXT)
        // about: pages carry an icon that draws nothing, rather than none; a
        // site without a favicon gets a generated letter icon from BrowserIcons,
        // so those two cases are the only gaps.
        if (tab.content.icon == null || tab.content.url.startsWith("about:")) {
            icon.setImageResource(mozilla.components.ui.icons.R.drawable.mozac_ic_globe_24)
            // The library's icon colour is white on this theme, which made the
            // globe all but invisible; the address grey matches the row.
            icon.setColorFilter(ContextCompat.getColor(icon.context, R.color.tab_item_url))
        } else {
            // Rows are recycled, so a real favicon must not inherit the tint.
            icon.clearColorFilter()
        }
    }

    override fun updateSelectedTabIndicator(showAsSelected: Boolean) {
        inner.updateSelectedTabIndicator(showAsSelected)
    }

    private companion object {
        const val MAX_ROW_TEXT = 256
    }
}
