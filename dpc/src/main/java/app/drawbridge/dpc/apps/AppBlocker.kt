package app.drawbridge.dpc.apps

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
import app.drawbridge.policy.model.Policy

/**
 * Removes apps that policy does not allow on the device.
 *
 * Two rules, both driven by the shared policy document:
 *  - anything whose package name is on `blocked_packages`;
 *  - any browser other than the one allowed browser.
 *
 * Browsers are *detected*, not listed: a package-name list of browsers is out of
 * date the moment someone publishes a new one, whereas the intent filter that
 * makes an app able to open `https://` links is what actually matters.
 */
class AppBlocker(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DrawbridgeDeviceAdminReceiver.componentName(appContext)

    enum class Action { NONE, UNINSTALLED, SUSPENDED, FAILED }

    /** Applies policy to a single package, e.g. one that has just been installed. */
    fun evaluate(packageName: String): Action {
        val policy = DrawbridgeApplication.policy(appContext).policy.value

        if (isProtected(packageName, policy)) return Action.NONE

        val reason = when {
            packageName in policy.blockedPackages -> "on the blocked package list"
            isBrowser(packageName) -> "is a browser other than ${policy.allowedBrowserPackage}"
            else -> return Action.NONE
        }

        Log.i(TAG, "Removing $packageName: $reason")
        return remove(packageName)
    }

    /**
     * Re-checks every installed package.
     *
     * Belt and braces for the install broadcast: the receiver only fires while
     * the filter service is alive, so anything installed during a restart would
     * otherwise be missed.
     */
    fun sweep(): Map<String, Action> {
        val installed = packageManager.getInstalledApplications(0)
        return installed
            .associate { it.packageName to evaluate(it.packageName) }
            .filterValues { it != Action.NONE }
    }

    /**
     * Removes [packageName]: uninstall for user-installed apps, hide for
     * preinstalled ones.
     *
     * `PackageInstaller.uninstall` fails on system apps — Chrome, Samsung
     * Internet and OEM browsers can never be uninstalled — so those are hidden
     * instead, which is what actually makes the DNS-only architecture safe: with
     * every other browser gone, no app is left that can run its own encrypted
     * DNS and route around the filter.
     */
    fun remove(packageName: String): Action {
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "Not device owner; cannot remove $packageName")
            return Action.FAILED
        }

        return if (isSystemPackage(packageName)) {
            hide(packageName)
        } else {
            uninstall(packageName)
        }
    }

    private fun uninstall(packageName: String): Action = try {
        packageManager.packageInstaller.uninstall(
            packageName,
            PackageRemovalReceiver.pendingIntent(appContext, packageName).intentSender,
        )
        Action.UNINSTALLED
    } catch (e: Exception) {
        Log.e(TAG, "Could not uninstall $packageName", e)
        // Uninstall fails on packages that turn out to be system apps after all;
        // hiding is the fallback that always works for a Device Owner.
        hide(packageName)
    }

    private fun hide(packageName: String): Action = try {
        val hidden = dpm.setApplicationHidden(admin, packageName, true)
        if (hidden) Action.SUSPENDED else Action.FAILED
    } catch (e: Exception) {
        Log.e(TAG, "Could not hide $packageName", e)
        Action.FAILED
    }

    /** Makes a previously hidden package usable again. Part of removal. */
    fun unhideAll() {
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .forEach { info ->
                runCatching {
                    if (dpm.isApplicationHidden(admin, info.packageName)) {
                        dpm.setApplicationHidden(admin, info.packageName, false)
                    }
                }
            }
    }

    /** True if [packageName] registers an activity that can open `https://` links. */
    fun isBrowser(packageName: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .any { it.activityInfo.packageName == packageName }
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun isProtected(packageName: String, policy: Policy): Boolean =
        packageName == appContext.packageName ||
            packageName == policy.allowedBrowserPackage ||
            packageName == BuildConfig.ALLOWED_BROWSER_PACKAGE ||
            packageName in policy.exemptPackages ||
            packageName in NEVER_TOUCH ||
            NEVER_TOUCH_PREFIXES.any { packageName.startsWith(it) }

    companion object {
        private const val TAG = "AppBlocker"

        /**
         * Packages that must survive no matter what the policy says.
         *
         * Some of these register `https://` intent filters — the Google app
         * handles AMP links, several launchers handle web search — and removing
         * or hiding them would leave an unusable phone with no sanctioned way to
         * fix it, since the removal UI lives on that same device.
         */
        private val NEVER_TOUCH = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.phone",
            "com.android.providers.settings",
            "com.android.providers.downloads",
            "com.android.certinstaller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.googlequicksearchbox",
            "com.google.android.webview",
            "com.android.webview",
        )

        /** Launchers, keyboards and system UI variants differ per OEM. */
        private val NEVER_TOUCH_PREFIXES = listOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
        )
    }
}
