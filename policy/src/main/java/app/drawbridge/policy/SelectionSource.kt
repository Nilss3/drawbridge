package app.drawbridge.policy

/**
 * Which profile and which options are in force, when that is decided somewhere
 * other than in this app.
 *
 * One signed document can be read very differently depending on what the parent
 * chose, and only drawbridge holds that choice. Without this, the browser on a
 * managed device filtered on the document's defaults while the DNS layer
 * filtered on the parent's actual selection — the two disagreed, and the visible
 * symptom was an option that plainly did nothing in the browser.
 *
 * @see PolicyConfig.selectionSource
 */
fun interface SelectionSource {

    /**
     * The current selection, or null if it cannot be read — because the app
     * holding it is not installed, or is not answering.
     *
     * Null means "fall back to what this app stores for itself", which for the
     * standalone browser is nothing at all and therefore the document's own
     * defaults. That fallback is the safe direction: the defaults are the strict
     * reading, so a browser that cannot reach drawbridge blocks more than it
     * might need to rather than less.
     *
     * Called on a background thread, and may do IPC.
     */
    fun read(): Selection?

    /**
     * A selection as published by whichever app owns it.
     *
     * [optionIds] is `null` for "nobody has chosen", which is not the same as an
     * empty list — see [PolicyStore.StoredState.optionIds].
     */
    data class Selection(
        val profileId: String?,
        val optionIds: List<String>?,
    )
}
