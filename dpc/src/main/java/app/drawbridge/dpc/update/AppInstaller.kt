package app.drawbridge.dpc.update

import android.content.Context
import android.content.pm.PackageInstaller
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

    /** Installs drawbridge's own update, if the policy names a newer one. */
    suspend fun checkAndInstallSelf(): Result = withContext(Dispatchers.IO) {
        val update = DrawbridgeApplication.policy(appContext).policy.value.appUpdate
            ?: return@withContext Result.UpToDate

        if (update.packageName != appContext.packageName) return@withContext Result.UpToDate
        if (update.versionCode <= versionCodeOf(appContext.packageName)) {
            return@withContext Result.UpToDate
        }

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
            val digest = Downloader().getToFile(update.url, staged)

            // Checked before the bytes reach the package installer: a mismatch
            // means this is not the APK the signed policy described, whatever
            // the reason.
            if (!digest.equalsIgnoringCase(update.sha256)) {
                return Result.Failed("checksum mismatch: expected ${update.sha256}, got $digest")
            }

            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply { setAppPackageName(update.packageName) }

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
    }
}
