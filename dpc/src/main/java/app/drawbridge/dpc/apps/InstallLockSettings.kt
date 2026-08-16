package app.drawbridge.dpc.apps

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Whether this phone closes at the lock: no new apps after it, and updates of
 * the apps already there still coming through.
 *
 * **Device-local, not policy**, for the same reason [BrowserSettings] and
 * `DisconnectSettings` are. The signed document says what the *web* may contain
 * and which apps are unsafe; this says whether this particular household wants
 * the phone's app list to stop growing at all. It is not a filter — it is a
 * different phone.
 *
 * **Promised on the website before it existed**, as *the app-install lock behind
 * "only certain apps"*, and built on the owner's call of 2026-08-16 after policy
 * 59 added twenty-two AI companion apps by hand. The diagnosis that produced it
 * is worth keeping next to the code: a curated blocklist is a filter for a phone
 * whose app store is wide open, new companion apps appear weekly with fresh
 * package ids, and a signed document updated by hand will always trail them. The
 * fix is upstream of the list.
 *
 * ### The snapshot, and why it can be absent
 *
 * The rule is *not* "an app installed after some date". It is **a closed set**:
 * the packages the phone carried at the moment it was last locked. That is what
 * makes updates need no special case at all — an update never adds a package
 * name that was not already there, so it is in the set by construction and the
 * rule never fires on it. `EXTRA_REPLACING` is a perfectly good boolean and this
 * deliberately does not depend on it, because the periodic sweep has no
 * broadcast to read it from and an app installed while the filter service was
 * down would be missed.
 *
 * **[snapshot] is null until the first lock, and that is not the same as
 * empty.** An empty set means *this phone carries nothing*, which would make
 * every package on it new and hand the blocker a rule that removes the entire
 * device. A snapshot that has never been taken has to answer "I cannot say"
 * rather than "nothing", so it is nullable and [AppBlocker] treats null as the
 * rule not applying. This is the same shape of mistake as keying enforcement on
 * `protectedSince` — a state that reads plausibly and means something else.
 *
 * ### Re-taken at every lock, which is what makes the unlock window the way in
 *
 * Unlock, install, lock again, and the app is in the set. That is the whole
 * user-facing answer to "how do I add an app to this phone", and it is the same
 * answer drawbridge already gives for every other change: it costs the key.
 */
class InstallLockSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * **Defaults off**, unlike the options, and deliberately so: it changes what
     * the phone *is* rather than what it filters. The screen that offers it has
     * to say what it costs before it is switched on — an app you have not
     * installed yet, you cannot install.
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * The packages the phone carried at the last lock, or null if it has never
     * been locked.
     *
     * Copied out of the preference rather than handed over: `getStringSet`
     * documents its return value as one the caller must not modify, and the
     * instance is shared with the preference's own cache.
     */
    val snapshot: Set<String>?
        get() = prefs.getStringSet(KEY_SNAPSHOT, null)?.toSet()

    /** When [snapshot] was taken, or 0. Diagnostics only. */
    val snapshotTakenAt: Long
        get() = prefs.getLong(KEY_SNAPSHOT_AT, 0)

    /**
     * Records the closed set. Called at every lock, before the lock sweep runs.
     *
     * The ordering is load-bearing and it is the reason this is not left to the
     * next periodic sweep: the sweep is what *enforces* the set, so a sweep that
     * ran against the previous lock's snapshot would remove exactly the app the
     * parent unlocked the phone to install.
     */
    fun take(packages: Collection<String>) {
        prefs.edit()
            .putStringSet(KEY_SNAPSHOT, packages.toSet())
            .putLong(KEY_SNAPSHOT_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Adds one package to the set, for an app drawbridge itself is installing.
     *
     * **Without this the phone would loop**, and it is the same loop the browser
     * choice already had to be guarded against: `required_apps` names herald,
     * herald is user-installed and so is *uninstalled* rather than hidden when a
     * policy change removes it, and the next poll fetches 230 MB to put it back
     * — which the install lock would then remove as an app that is not in the
     * snapshot, moments after drawbridge asked for it.
     *
     * It is honest as well as necessary. drawbridge putting an app on the phone
     * is the parent's decision arriving by proxy: the APK is named by the signed
     * policy they chose and pinned by checksum, so nothing arrives this way that
     * they did not already consent to.
     *
     * A no-op before the first lock — there is no set to add to yet, and
     * [AppBlocker] is removing nothing in that window anyway.
     */
    fun allow(packageName: String) {
        val current = snapshot ?: return
        if (packageName in current) return
        prefs.edit().putStringSet(KEY_SNAPSHOT, current + packageName).apply()
    }

    /** Part of the sanctioned removal flow: a set means nothing once nothing enforces it. */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "drawbridge_install_lock"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_SNAPSHOT_AT = "snapshot_at"

        /**
         * Packages drawbridge is installing right now — downloaded, committed,
         * not yet on the phone.
         *
         * **Two things read it, and both are about a window rather than a
         * decision.** `DeviceOwnerManager` stands `DISALLOW_INSTALL_APPS` down
         * while it is non-empty, and [AppBlocker.closeTheInstalledSet] counts
         * these as present when it records the set.
         *
         * That second reader is the one that is easy to miss and it cost a
         * re-read to find. herald is over 200 MB, so *"choose the allowed
         * browsers, then lock"* commits the session and then takes minutes to
         * land — and the lock happens in the middle. Without this the lock would
         * enumerate the installed packages, not find the herald it had just
         * asked for, and write a snapshot that excludes it: drawbridge removing
         * its own browser moments after fetching it, which is precisely the loop
         * [allow] exists to prevent, arriving by a different door.
         *
         * In memory rather than in the preference, and losing it to process
         * death fails safe in both directions: the restriction comes back on at
         * the next start, and a name that never got into the set is put there by
         * the install's own result broadcast.
         */
        private val installing = CopyOnWriteArraySet<String>()

        val ownInstallsInFlight: Set<String> get() = installing.toSet()

        fun beginOwnInstall(packageName: String) {
            installing.add(packageName)
        }

        fun endOwnInstall(packageName: String) {
            installing.remove(packageName)
        }

        /**
         * Whether the install lock has anything to say about this package.
         *
         * A pure function of its three inputs, like [AppBlocker.actsNow],
         * [AppBlocker.restorable] and [BrowserSettings.allowedBrowsers], because
         * it decides what gets uninstalled and this project has now paid several
         * times over for a rule that could only be checked by holding a phone.
         *
         * Note what is *not* here: the lock state. This answers "does policy
         * want this package gone", and [AppBlocker.deferred] answers "now or at
         * the lock" — the same division of labour every other rule in the
         * blocker uses. Keeping them apart is what lets an app installed during
         * an unlock survive until the lock re-takes the snapshot, at which point
         * it is in the set and the question never arises again.
         *
         * Nor is whether the package is preinstalled. That is a question about
         * the device rather than about the rule, so it is asked by the caller —
         * see `AppBlocker.outsideTheInstalledSet`, which limits this to
         * user-installed apps so that an Android upgrade adding a system app
         * cannot subtract from the phone.
         */
        fun outsideTheSet(
            enabled: Boolean,
            snapshot: Set<String>?,
            packageName: String,
        ): Boolean {
            if (!enabled) return false
            // Never locked, so there is no set. Not "the set is empty", which is
            // what a non-nullable default would have quietly meant, and which
            // would disallow every package on the device.
            val closed = snapshot ?: return false
            return packageName !in closed
        }
    }
}
