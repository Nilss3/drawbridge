package app.drawbridge.dpc.apps

import android.content.Context
import android.content.SharedPreferences
import app.drawbridge.policy.model.Policy

/**
 * How much browser this phone is allowed to have.
 *
 * **Device-local, not policy**, for the same reason the disconnect philosophy is
 * (see `DisconnectSettings`): the signed document says which browsers are *safe*
 * to allow — the ones that carry no in-browser proxy and no secure DNS of their
 * own — and this says how many of those safe ones this particular household
 * wants. A document signed by this project's key cannot know that somebody is
 * struggling with browsing itself.
 *
 * The two compose rather than compete. [allowedBrowsers] never returns a package
 * the policy did not already sanction, so narrowing here can make a phone
 * stricter and never looser.
 *
 * Promised on the website before it existed: *"by default drawbridge allows a
 * limited list of browsers… you can also choose to use herald mono only… finally,
 * you can choose to have no browser at all."*
 */
class BrowserSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The three choices, in the order the configuration screen shows them. */
    enum class Choice {
        /** Every browser the policy sanctions. The default, and what every phone did before this existed. */
        ALL,

        /**
         * herald mono and nothing else — black and white, reader mode, one tab.
         *
         * For browsing addiction rather than for what browsing reaches: the
         * filter is the same either way, and this is about the browser being
         * boring.
         */
        MONO_ONLY,

        /**
         * No browser at all.
         *
         * The screen warns what this costs, because it is not obvious and it is
         * not recoverable in the moment: apps that sign in through a browser
         * cannot, so anything not already logged in stays logged out.
         */
        NONE,
        ;

        companion object {
            fun from(name: String?): Choice = entries.firstOrNull { it.name == name } ?: ALL
        }
    }

    var choice: Choice
        get() = Choice.from(prefs.getString(KEY_CHOICE, null))
        set(value) = prefs.edit().putString(KEY_CHOICE, value.name).apply()

    // There was a `defaultBrowser` here, holding which allowed browser web links
    // should open in. It is gone, and so is the picker that fed it: drawbridge
    // no longer decides that at all. Android's own default-app machinery does,
    // which is a question it already asks well — see
    // `DeviceOwnerManager.releaseDefaultBrowser`.

    companion object {
        private const val PREFS_NAME = "drawbridge_browsers"
        private const val KEY_CHOICE = "choice"

        /**
         * The browsers this choice leaves allowed, out of the ones the policy
         * sanctions.
         *
         * **Intersected with the policy rather than named outright**, which
         * matters for [Choice.MONO_ONLY]: if a document ever stops sanctioning
         * herald mono, this must stop allowing it too rather than quietly
         * out-ranking the document. Narrowing is the only direction available.
         *
         * A pure function of its two inputs, like [AppBlocker.actsNow] and
         * [AppBlocker.restorable], because it decides what gets uninstalled.
         */
        fun allowedBrowsers(policy: Policy, choice: Choice): Set<String> = when (choice) {
            Choice.ALL -> policy.browserPackages
            Choice.MONO_ONLY -> policy.browserPackages.intersect(setOf(MONO_PACKAGE))
            Choice.NONE -> emptySet()
        }

        /**
         * herald mono's package name.
         *
         * A constant rather than a policy field: this is drawbridge's own second
         * browser, the option exists to name *it* specifically, and a document
         * that could point this at an arbitrary package would be a document that
         * could point "the minimal browser" at anything at all.
         */
        const val MONO_PACKAGE = "app.drawbridge.heraldmono"
    }
}
