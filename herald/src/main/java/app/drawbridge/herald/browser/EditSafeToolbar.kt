package app.drawbridge.herald.browser

import mozilla.components.concept.toolbar.Toolbar

/**
 * Stops the address bar from wiping what is being typed into it.
 *
 * `ToolbarPresenter` re-renders on **every** `BrowserState` update, and one of
 * the things it does unconditionally is `toolbar.setSearchTerms(tab.searchTerms)`.
 * `BrowserToolbar.setSearchTerms` applies that only while the toolbar is in edit
 * mode — that is, only while someone is typing — and applying it means
 * `EditToolbar.editSuggestion`, which calls `updateUrl` and *replaces the field's
 * text*, then moves the cursor to the end.
 *
 * For an ordinary page the search terms are empty, so the field is emptied. The
 * bug reads as "the URL bar sometimes clears while I type", and it is
 * intermittent only because it needs a state update to land mid-keystroke:
 * a page still loading in another tab, uBlock Origin's badge counter ticking, the
 * periodic session autosave. Nothing about it is herald's doing, and it affects
 * both editions.
 *
 * The fix is to hold the update back until editing finishes rather than to drop
 * it. `BrowserToolbar` keeps the last search terms it was given and uses them to
 * prefill the field the *next* time edit mode is entered — which is the only
 * thing they are for here, since herald renders URLs rather than search terms in
 * the display toolbar. Suppressing them outright would work for this bug and
 * would quietly lose that prefill; suppressing them only while editing keeps
 * both, because a page that loads while nobody is typing still updates them.
 *
 * Wrapping rather than subclassing because `BrowserToolbar` is final —
 * `ToolbarFeature` takes the `Toolbar` interface, which is the seam this uses.
 *
 * **This owns the toolbar's single edit listener.** Anything else that needs
 * `setOnEditListener` has to go through here, or one of the two will silently
 * replace the other.
 */
class EditSafeToolbar(private val delegate: Toolbar) : Toolbar by delegate {

    @Volatile
    private var editing = false

    init {
        delegate.setOnEditListener(object : Toolbar.OnEditListener {
            override fun onStartEditing() {
                editing = true
            }

            override fun onStopEditing() {
                editing = false
            }

            override fun onCancelEditing(): Boolean {
                editing = false
                return true
            }
        })
    }

    override fun setSearchTerms(searchTerms: String) {
        if (editing) return
        delegate.setSearchTerms(searchTerms)
    }
}
