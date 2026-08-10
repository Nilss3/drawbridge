package app.drawbridge.dpc.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInstaller

/**
 * The result of the last install, written down so a screen can report it.
 *
 * `PackageInstaller` answers asynchronously, through a broadcast that arrives
 * well after the call that started the install has returned — and for
 * drawbridge's own update, often after the process that started it has been
 * replaced. Without somewhere to put that answer, the update screen could only
 * ever say "started", which is precisely the report a parent cannot act on: a
 * Play Protect refusal and a successful install look identical from there.
 *
 * Deliberately tiny and deliberately not history. One slot, overwritten,
 * carrying only what a screen needs to say what happened.
 */
class InstallOutcome(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Outcome(
        val packageName: String,
        val versionCode: Int,
        val status: Int,
        val message: String?,
        val at: Long,
    ) {
        val succeeded: Boolean get() = status == PackageInstaller.STATUS_SUCCESS

        /**
         * Whether this looks like the phone refusing the install rather than the
         * download or the package being wrong.
         *
         * Play Protect surfaces as an abort or a block, depending on the Android
         * build, and neither carries a machine-readable reason — so this is a
         * hint for choosing wording, not a diagnosis.
         */
        val looksBlocked: Boolean
            get() = status == PackageInstaller.STATUS_FAILURE_ABORTED ||
                status == PackageInstaller.STATUS_FAILURE_BLOCKED
    }

    fun record(packageName: String, versionCode: Int, status: Int, message: String?) {
        prefs.edit()
            .putString(KEY_PACKAGE, packageName)
            .putInt(KEY_VERSION, versionCode)
            .putInt(KEY_STATUS, status)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun latest(): Outcome? {
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        return Outcome(
            packageName = packageName,
            versionCode = prefs.getInt(KEY_VERSION, 0),
            status = prefs.getInt(KEY_STATUS, -1),
            message = prefs.getString(KEY_MESSAGE, null),
            at = prefs.getLong(KEY_AT, 0),
        )
    }

    /** Cleared before starting an install, so the next answer cannot be an old one. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_NAME = "drawbridge_install_outcome"
        const val KEY_PACKAGE = "package"
        const val KEY_VERSION = "version_code"
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"
        const val KEY_AT = "at"
    }
}
