package app.drawbridge.dpc.apps

import android.annotation.SuppressLint
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
 * Three rules, all driven by the shared policy document:
 *  - anything whose package name is on `blocked_packages`;
 *  - any browser the policy does not name — one or several, see
 *    [Policy.browserPackages];
 *  - in allowlist mode (`allowed_packages` set, typically by a profile), any
 *    *user-installed* app that is not on the list.
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
            isBrowser(packageName) ->
                "is a browser, and policy allows only ${policy.browserPackages.joinToString()}"
            notAllowed(packageName, policy) -> "not on this profile's allowed list"
            else -> return Action.NONE
        }

        Log.i(TAG, "Removing $packageName: $reason")
        return remove(packageName)
    }

    /**
     * Allowlist mode: true when the policy names an allowed set and this package
     * is outside it.
     *
     * Deliberately limited to user-installed apps. Flipping the default action
     * from "remove what is listed" to "remove what is not" is the one rule that
     * could take the phone apart — a list that forgets the dialer, the camera or
     * the OEM's keyboard would otherwise hide them — and no allowlist a parent
     * writes will name the hundred packages an Android build needs. Preinstalled
     * apps stay reachable through `blocked_packages`, which hides rather than
     * uninstalls and is therefore reversible.
     */
    private fun notAllowed(packageName: String, policy: Policy): Boolean {
        val allowed = policy.allowedPackages ?: return false
        return !isSystemPackage(packageName) && packageName !in allowed
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

    /**
     * Lint insists on DELETE_PACKAGES or REQUEST_DELETE_PACKAGES here, and both
     * are deliberately absent from the manifest — the first is
     * signature|privileged and can never be granted to a sideloaded app, and the
     * second only governs the confirmation dialog a Device Owner never sees.
     * The platform annotation does not model the Device Owner waiver.
     *
     * Verified on the provisioned emulator, 2026-08-09: with neither permission
     * declared, this call uninstalled a disallowed browser. Do not "fix" the
     * warning by adding the permission back — declaring install-adjacent
     * permissions is what started the Play Protect problem in the first place.
     * See docs/handoff.md.
     */
    @SuppressLint("MissingPermission")
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
            packageName in policy.browserPackages ||
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
