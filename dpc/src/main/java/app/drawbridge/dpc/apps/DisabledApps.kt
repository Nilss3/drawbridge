package app.drawbridge.dpc.apps

import android.content.Context
import android.content.SharedPreferences

/**
 * The apps the parent had switched off in Settings when they locked the phone.
 *
 * **The hole this closes was reported from use on 2026-08-25 and measured on
 * 2026-09-08.** The flow is the one *No other apps* is for: a parent prunes the
 * phone, disables what they do not want, flicks the switch, and locks. What the
 * switch guarantees is that no *new* app arrives. What nothing guaranteed is
 * that the ones already disabled stay that way, and on the API 36 emulator with
 * a real Device Owner they did not: disable the clock, lock drawbridge, open
 * Settings for that package, and the **Enable** button is there. Tapping it
 * brought the app back on a phone that was still locked, with the key still
 * stored.
 *
 * Android agrees, and there is nothing to fix on its side:
 * `DISALLOW_APPS_CONTROL` is the only restriction that would have stopped it and
 * drawbridge does not set it, because it stops the parent managing apps too and
 * may take the Play Store's own update path with it. That path is the thing
 * [InstallLockSettings] is careful to keep, so the blunt instrument was never
 * available here.
 *
 * ### A set recorded at the lock, like the installed one, and enforced by hiding
 *
 * This is deliberately the same shape as [InstallLockSettings]: a set taken at
 * the moment of locking, and enforced by the ordinary sweep afterwards. Where it
 * differs is what enforcement *means*. drawbridge cannot re-disable an app —
 * `setApplicationEnabledSetting` is for an app's own components, and no Device
 * Owner API disables a package on the user's behalf. What it can do is
 * [AppBlocker.hideOrSuspend], which is the same lever every reversible removal
 * here already uses, and which the child cannot undo from Settings because a
 * hidden app is not in the list at all.
 *
 * So the sequence is: disabled at the lock, re-enabled by somebody, hidden by
 * the next sweep.
 *
 * ### Empty rather than null, unlike the installed set
 *
 * [InstallLockSettings.snapshot] is nullable and has to be: an empty *installed*
 * set means "this phone carries nothing", which would hand the blocker a rule
 * that removes the whole device, so "never taken" has to be a third state. Here
 * the rule is additive — it withholds named packages and nothing else — so an
 * empty set and an absent one both mean *withhold nothing*, and there is no
 * third state worth modelling.
 *
 * ### Not gated on the switch
 *
 * For the same reason [AppBlocker.closeTheInstalledSet] is not. A parent who
 * disables an app and then seals the phone has said what they want, and nothing
 * on the configuration screen tells them that only holds if some other switch is
 * on. Recording it costs one package enumeration on a path that is already
 * enumerating them.
 */
class DisabledApps(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The packages that were user-disabled when the phone was last locked.
     *
     * Copied out of the preference rather than handed over: `getStringSet`
     * documents its return as one the caller must not modify, and the instance
     * is shared with the preference's own cache.
     */
    val atLastLock: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED, null)?.toSet() ?: emptySet()

    /** When [atLastLock] was taken, or 0. Diagnostics only. */
    val takenAt: Long
        get() = prefs.getLong(KEY_DISABLED_AT, 0)

    /** Records the set. Called at every lock, before the sweep that enforces it. */
    fun take(packages: Collection<String>) {
        prefs.edit()
            .putStringSet(KEY_DISABLED, packages.toSet())
            .putLong(KEY_DISABLED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Part of the sanctioned removal flow: a set means nothing once nothing enforces it. */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "drawbridge_disabled_apps"
        private const val KEY_DISABLED = "disabled"
        private const val KEY_DISABLED_AT = "disabled_at"

        /**
         * Whether this package should be withheld right now, as a function of
         * its inputs.
         *
         * Pure, and separate from the code that acts on it, for the same reason
         * [InstallLockSettings.outsideTheSet] is: the rule is four booleans and
         * every one of them can be got backwards, while the code around it needs
         * a provisioned device to run at all.
         *
         * @param inSet the parent had it switched off when they locked.
         * @param stillDisabled it is *still* switched off, so nobody has undone
         *   anything and there is nothing to do.
         * @param alreadyWithheld drawbridge has already hidden or suspended it,
         *   whether for this reason or because the policy disallows it.
         */
        fun withholdNow(
            inSet: Boolean,
            stillDisabled: Boolean,
            alreadyWithheld: Boolean,
        ): Boolean = inSet && !stillDisabled && !alreadyWithheld

        /**
         * Whether a withheld package should be given back on unlocking.
         *
         * **Only what drawbridge withheld for *this* reason**, which is why
         * [policyDisallows] is an input rather than an afterthought. A package
         * the parent disabled *and* the policy hides — WhatsApp with its switch
         * off, say — is hidden for two reasons at once, and giving it back
         * because one of them lapsed would hand the phone an app the signed
         * document still refuses.
         *
         * **What this deliberately does not do is re-disable it.** Nothing can:
         * the app comes back enabled, and the parent sees it in Settings and can
         * switch it off again before the next lock. That is a real loss of
         * fidelity and it is the honest one — the alternative is leaving an app
         * hidden through an unlock, where the parent cannot see it, cannot
         * manage it, and has no way to find out why it is gone.
         */
        fun releaseNow(
            inSet: Boolean,
            withheld: Boolean,
            policyDisallows: Boolean,
        ): Boolean = inSet && withheld && !policyDisallows
    }
}
