package app.drawbridge.dpc.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.policy.model.AppUpdate
import app.drawbridge.policy.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Installs APKs named by the signed policy: drawbridge's own updates, and the
 * apps a managed device is required to have.
 *
 * There is no Play Store here, so there is no free install or update channel —
 * but a Device Owner can install a package silently. Because the APK is named by
 * the *signed* policy and pinned by checksum, a compromised download host cannot
 * substitute a different build.
 *
 * Android still requires an update to be signed with the same key as the
 * installed app. **Losing the release keystore strands every deployed device on
 * its current version**, recoverable only by re-provisioning from scratch.
 */
class AppInstaller(context: Context) {

    private val appContext = context.applicationContext

    sealed interface Result {
        data object UpToDate : Result
        data object Started : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * The update the signed policy names for drawbridge itself, or null when
     * there is nothing newer than what is running.
     *
     * A pure query: it reads the policy and installs nothing. drawbridge used to
     * install its own updates from the periodic worker, silently, which is the
     * right design everywhere except where it turned out to be running — Play
     * Protect refuses that install on any phone with a Google account, and five
     * rounds of experiment established that nothing in the APK moves it. A
     * silent retry every three hours just fails every three hours.
     *
     * So updating is something the parent starts, and this is what tells the
     * screens there is something to start. See [installSelfUpdate] and
     * docs/handoff.md.
     */
    fun availableSelfUpdate(): AppUpdate? {
        val update = DrawbridgeApplication.policy(appContext).policy.value.appUpdate ?: return null
        if (update.packageName != appContext.packageName) return null
        if (update.versionCode <= versionCodeOf(appContext.packageName)) return null
        return update
    }

    /**
     * Installs what [availableSelfUpdate] found, on the parent's say-so.
     *
     * Expected to fail while Play Protect is on, and the screen that calls this
     * says so before the parent presses anything.
     */
    suspend fun installSelfUpdate(): Result = withContext(Dispatchers.IO) {
        val update = availableSelfUpdate() ?: return@withContext Result.UpToDate
        Log.i(TAG, "Updating drawbridge to version ${update.versionCode}")
        install(update)
    }

    /**
     * Installs any required app that is missing or out of date — in practice,
     * herald.
     *
     * This is what makes QR provisioning a single step. By the time drawbridge
     * has taken over it has already removed or hidden every browser, so nothing
     * else on the device could fetch herald: without this the device would be
     * left with no way to browse at all.
     */
    suspend fun installMissingRequiredApps(): Map<String, Result> = withContext(Dispatchers.IO) {
        DrawbridgeApplication.policy(appContext).policy.value.requiredApps
            .filter { it.matchesThisDevice() }
            // The per-ABI splits of one app all declare the same package name,
            // and only the device's own ABI survives the filter above.
            .distinctBy { it.packageName }
            .filter { it.versionCode > versionCodeOf(it.packageName) }
            .associate { required ->
                Log.i(TAG, "Installing required app ${required.packageName}")
                required.packageName to install(required)
            }
    }

    private fun AppUpdate.matchesThisDevice(): Boolean =
        abi == null || Build.SUPPORTED_ABIS.any { it.equals(abi, ignoreCase = true) }

    private fun versionCodeOf(packageName: String): Int = try {
        appContext.packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        // Not installed — for a required app, exactly the case to act on.
        0
    }

    private fun install(update: AppUpdate): Result {
        val staged = File(appContext.cacheDir, "${update.packageName}-${update.versionCode}.apk")
        try {
            // Downloader's default cap is sized for policy documents and
            // blocklists. An APK carrying a browser engine is far larger —
            // herald is over 200 MB — so it needs its own ceiling, kept low
            // enough that a hostile URL still cannot fill the disk.
            val digest = Downloader(maxBytes = MAX_APK_BYTES).getToFile(update.url, staged)

            // Checked before the bytes reach the package installer: a mismatch
            // means this is not the APK the signed policy described, whatever
            // the reason.
            if (!digest.equalsIgnoringCase(update.sha256)) {
                return Result.Failed("checksum mismatch: expected ${update.sha256}, got $digest")
            }

            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(update.packageName)

                // Says what this session actually is: a Device Owner applying
                // the policy it was provisioned with, not a user sideloading
                // something. Untested as a cure for Play Protect refusing
                // drawbridge's own updates, and it can only ever be tested by an
                // already-installed build — the session is described by whatever
                // is doing the installing. True regardless of whether it helps.
                setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(update.packageName, 0, staged.length()).use { output ->
                    staged.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(
                    InstallResultReceiver
                        .pendingIntent(appContext, update.packageName, update.versionCode)
                        .intentSender,
                )
            }
            return Result.Started
        } catch (e: Exception) {
            Log.e(TAG, "Install of ${update.packageName} failed", e)
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            staged.delete()
        }
    }

    /** Hex case varies between tools; both values are public, so constant time is not needed. */
    private fun String.equalsIgnoringCase(other: String): Boolean =
        MessageDigest.isEqual(lowercase().toByteArray(), other.lowercase().toByteArray())

    private companion object {
        const val TAG = "AppInstaller"

        /** Comfortably above herald's ~220 MB, far below filling a phone. */
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
