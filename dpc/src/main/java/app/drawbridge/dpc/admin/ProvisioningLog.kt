package app.drawbridge.dpc.admin

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A timestamped record of what happened during provisioning, kept on the device
 * and readable from drawbridge's own screen.
 *
 * QR provisioning is the hardest thing in this project to observe. It runs once,
 * costs a factory reset per attempt, and — because the restrictions switch USB
 * debugging off, and a freshly reset phone has developer options disabled
 * anyway — there is no adb to read logcat with. On 2026-08-07 that left three
 * plausible causes for a failure and no way to tell them apart; two factory
 * resets were spent inferring from symptoms.
 *
 * So the DPC keeps its own record. It survives the reboot that clears logcat,
 * and [read] renders it into the configuration screen on builds that carry
 * `RETAIN_ADB_ACCESS`, which is where anyone would be looking.
 *
 * Deliberately dumb: append-only lines in a small file, no rotation beyond a
 * size cap, no structure. Anything cleverer is a thing that can fail during the
 * one window it exists to observe.
 */
object ProvisioningLog {

    private const val TAG = "ProvisioningLog"
    private const val FILE_NAME = "provisioning-log.txt"
    private const val MAX_BYTES = 32 * 1024
    private const val SETTING_USER_SETUP_COMPLETE = "user_setup_complete"

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Appends one event, with the setup-wizard state at the moment it happened.
     *
     * That last part is the whole point: the question is never only "did this
     * callback run" but "what did the device think its setup state was when it
     * did".
     */
    fun record(context: Context, event: String) {
        val line = "${stamp.format(Date())}  setup=${setupComplete(context)}  $event"
        Log.i(TAG, line)
        runCatching {
            val file = file(context)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText(line + "\n")
        }.onFailure { Log.e(TAG, "Could not write provisioning log", it) }
    }

    /** The record so far, oldest first, or null if nothing was ever written. */
    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.trimEnd() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * `Settings.Secure.USER_SETUP_COMPLETE`, as 1, 0 or `?`.
     *
     * Android treats a device whose setup has never completed very differently:
     * SystemUI disables the notification shade and Settings closes itself
     * immediately. A DPC that finishes provisioning while this is still 0 has
     * left the device mid-wizard, however healthy it looks from the DPC's side.
     */
    fun setupComplete(context: Context): String =
        runCatching {
            // Spelled out rather than referenced: Settings.Secure.USER_SETUP_COMPLETE
            // is @hide, so the constant is not in the public SDK even though the
            // value is readable by anyone. The string has been stable since
            // Android 4.2 and is what every DPC and OEM setup wizard uses.
            Settings.Secure.getInt(context.contentResolver, SETTING_USER_SETUP_COMPLETE)
                .toString()
        }.getOrDefault("?")

    /** True when the setup wizard has finished and the device is a normal phone. */
    fun isSetupComplete(context: Context): Boolean = setupComplete(context) == "1"

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
