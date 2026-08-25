package app.drawbridge.herald.browser

import mozilla.components.concept.toolbar.Toolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address bar clearing itself mid-typing was the whole of one bug report,
 * and the cause was a single unconditional call in a library. This pins the rule
 * that stops it — and, since 2026-08-25, the second job the same wrapper does:
 * putting the on-screen keyboard away when editing ends.
 */
class EditSafeToolbarTest {

    /**
     * Records what reaches the real toolbar, and lets the test drive the edit
     * listener the wrapper installs the way `BrowserToolbar` would.
     */
    private class RecordingToolbar : Toolbar {
        /** Named to avoid a JVM signature clash with `setSearchTerms`. */
        var received: String? = null
        var editListener: Toolbar.OnEditListener? = null

        override fun setSearchTerms(searchTerms: String) {
            received = searchTerms
        }

        override fun setOnEditListener(listener: Toolbar.OnEditListener) {
            editListener = listener
        }

        // Nothing else is exercised; the wrapper delegates it untouched.
        override var title: String = ""
        override var url: CharSequence = ""
        override var private: Boolean = false
        override var siteInfo: Toolbar.SiteInfo = Toolbar.SiteInfo.INSECURE
        override var highlight: Toolbar.Highlight = Toolbar.Highlight.NONE
        override var siteTrackingProtection: Toolbar.SiteTrackingProtection =
            Toolbar.SiteTrackingProtection.OFF_GLOBALLY

        override fun displayProgress(progress: Int) = Unit
        override fun onBackPressed(): Boolean = false
        override fun onStop() = Unit
        override fun setOnUrlCommitListener(listener: (String) -> Boolean) = Unit
        override fun setAutocompleteListener(
            filter: suspend (String, mozilla.components.concept.toolbar.AutocompleteDelegate) -> Unit,
        ) = Unit
        override fun addBrowserAction(action: Toolbar.Action) = Unit
        override fun removeBrowserAction(action: Toolbar.Action) = Unit
        override fun removePageAction(action: Toolbar.Action) = Unit
        override fun removeNavigationAction(action: Toolbar.Action) = Unit
        override fun invalidateActions() = Unit
        override fun addPageAction(action: Toolbar.Action) = Unit
        override fun addNavigationAction(action: Toolbar.Action) = Unit
        override fun addEditActionStart(action: Toolbar.Action) = Unit
        override fun addEditActionEnd(action: Toolbar.Action) = Unit
        override fun removeEditActionEnd(action: Toolbar.Action) = Unit
        override fun hideMenuButton() = Unit
        override fun showMenuButton() = Unit
        override fun setDisplayHorizontalPadding(horizontalPadding: Int) = Unit
        override fun displayMode() = Unit
        override fun editMode(cursorPlacement: Toolbar.CursorPlacement) = Unit
        override fun dismissMenu() = Unit
        override fun enableScrolling() = Unit
        override fun disableScrolling() = Unit
        override fun expand() = Unit
        override fun collapse() = Unit
    }

    private val real = RecordingToolbar()
    private var keyboardDismissals = 0
    private val toolbar = EditSafeToolbar(real) { keyboardDismissals++ }

    private fun startEditing() = real.editListener!!.onStartEditing()
    private fun stopEditing() = real.editListener!!.onStopEditing()

    @Test
    fun `search terms reach the toolbar while nobody is typing`() {
        toolbar.setSearchTerms("otters")

        assertEquals("otters", real.received)
    }

    @Test
    fun `search terms are held back while the address bar is being edited`() {
        startEditing()

        // This is the call that used to empty the field: the presenter re-renders
        // on any state update, and an ordinary page has no search terms.
        toolbar.setSearchTerms("")

        assertNull(real.received)
    }

    @Test
    fun `a page with search terms cannot overwrite what is being typed either`() {
        // Starting to type a URL from a search-results page: suppressing only
        // the empty case would still have let this one through and replaced the
        // half-typed URL with the old query.
        startEditing()

        toolbar.setSearchTerms("otters")

        assertNull(real.received)
    }

    @Test
    fun `updates resume once editing ends`() {
        startEditing()
        toolbar.setSearchTerms("")
        stopEditing()

        toolbar.setSearchTerms("otters")

        assertEquals("otters", real.received)
    }

    @Test
    fun `cancelling editing also resumes them`() {
        startEditing()
        real.editListener!!.onCancelEditing()

        toolbar.setSearchTerms("otters")

        assertEquals("otters", real.received)
    }

    @Test
    fun `the keyboard goes away when editing stops`() {
        startEditing()
        assertEquals(0, keyboardDismissals)

        stopEditing()

        assertEquals(1, keyboardDismissals)
    }

    @Test
    fun `and when editing is cancelled`() {
        startEditing()

        real.editListener!!.onCancelEditing()

        assertEquals(1, keyboardDismissals)
    }

    @Test
    fun `but not merely because editing started`() {
        startEditing()

        // The keyboard is what somebody is typing on at this point. Dismissing
        // it here would be the opposite bug.
        assertEquals(0, keyboardDismissals)
    }
}
