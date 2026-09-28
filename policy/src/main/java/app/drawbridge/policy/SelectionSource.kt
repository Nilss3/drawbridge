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

        /**
         * The `versionCode` of the app that owns this selection — drawbridge.
         *
         * **A version-gated policy fragment has to be judged by drawbridge's
         * build, not by the build of whatever app is reading the document.**
         * herald has its own numbering entirely, so a fragment written for
         * drawbridge 53 would be read by herald 19 as *not yet*, or by a herald
         * numbered past it as *already* — and either way the browser and the DNS
         * layer would disagree about what is blocked. That disagreement is the
         * exact failure this whole interface was built to stop.
         *
         * Zero when the owning app is too old to publish it, which reads as *no
         * fragment applies*: a browser that cannot tell blocks nothing extra and
         * leaves the DNS layer, which can tell, to do it.
         */
        val ownerVersionCode: Int = 0,
    )
}
