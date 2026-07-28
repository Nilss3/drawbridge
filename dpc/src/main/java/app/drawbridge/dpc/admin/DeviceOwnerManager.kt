package app.drawbridge.dpc.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import app.drawbridge.dpc.BuildConfig

/**
 * Everything that requires Device Owner privilege.
 *
 * Every call here is a no-op unless [isDeviceOwner] — the app is designed to run
 * un-provisioned too (it just shows a setup screen), so callers never have to
 * guard.
 */
class DeviceOwnerManager(context: Context) {

    private val appContext = context.applicationContext
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin: ComponentName = DrawbridgeDeviceAdminReceiver.componentName(appContext)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(appContext.packageName)

    /**
     * The full lockdown: the always-on VPN plus the restriction set.
     *
     * Safe to call repeatedly; it is invoked at provisioning time and again on
     * every boot, so a restriction cleared by an OS upgrade comes back.
     */
    fun applyManagedDevicePolicy(vpnPackage: String = appContext.packageName) {
        if (!isDeviceOwner) {
            Log.w(TAG, "Not device owner; skipping policy application")
            return
        }

        applyUserRestrictions()
        enableAlwaysOnVpn(vpnPackage)
        setDefaultBrowser()
    }

    /**
     * Pins the filter as the device's always-on VPN.
     *
     * **Lockdown is deliberately off**, which is a change from the original
     * design. Lockdown means "drop any traffic that does not go through the VPN
     * interface" — and this filter intentionally routes only DNS into its
     * tunnel, so with lockdown enabled every non-DNS packet on the device is
     * dropped and the phone has no working internet at all. (Verified: names
     * resolve, then `connect()` fails with EPERM for every app.) The two
     * settings are mutually exclusive unless the VPN carries all traffic, which
     * would mean the full userspace TCP/IP stack that v1 explicitly avoids.
     *
     * What is lost is the boot-time gap: between the network coming up and this
     * service establishing its tunnel, DNS falls back to the system resolvers
     * and is unfiltered. What holds the line instead is always-on itself (the
     * platform restarts the service), `START_STICKY`, and
     * [UserManager.DISALLOW_CONFIG_VPN] so no other VPN can be configured.
     */
    fun enableAlwaysOnVpn(vpnPackage: String = appContext.packageName): Boolean {
        if (!isDeviceOwner) return false
        return try {
            dpm.setAlwaysOnVpnPackage(admin, vpnPackage, /* lockdownEnabled = */ false)
            true
        } catch (e: Exception) {
            // UnsupportedOperationException on devices whose VPN stack refuses
            // lockdown, NameNotFoundException if the package is missing.
            Log.e(TAG, "Could not enable always-on VPN", e)
            false
        }
    }

    fun disableAlwaysOnVpn() {
        if (!isDeviceOwner) return
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            .onFailure { Log.e(TAG, "Could not clear always-on VPN", it) }
    }

    /**
     * Applies the restriction set.
     *
     * Three of these are easy to leave out and each one on its own defeats the
     * whole filter:
     *  - [UserManager.DISALLOW_SAFE_BOOT]: safe mode disables third-party apps,
     *    including the VPN service.
     *  - [UserManager.DISALLOW_ADD_USER] and friends: always-on VPN is per-user,
     *    so a guest profile would get unfiltered network with no restrictions.
     *  - [UserManager.DISALLOW_DEBUGGING_FEATURES]: ADB can clear device owner.
     */
    fun applyUserRestrictions() {
        if (!isDeviceOwner) return
        restrictionsToApply().forEach { restriction ->
            runCatching { dpm.addUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not set restriction $restriction", it) }
        }
    }

    private fun restrictionsToApply(): List<String> =
        if (BuildConfig.RETAIN_ADB_ACCESS) {
            Log.w(TAG, "Debug build: leaving USB debugging enabled so adb keeps working")
            MANAGED_RESTRICTIONS - UserManager.DISALLOW_DEBUGGING_FEATURES
        } else {
            MANAGED_RESTRICTIONS
        }

    fun clearUserRestrictions() {
        if (!isDeviceOwner) return
        MANAGED_RESTRICTIONS.forEach { restriction ->
            runCatching { dpm.clearUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not clear restriction $restriction", it) }
        }
    }

    /**
     * Blocks the kid from adding their own Google account.
     *
     * Set only once the parent's account is already on the device: it is what
     * makes the Factory Reset Protection backstop hold. If the child's account
     * were ever added, they could satisfy FRP themselves after a recovery-mode
     * wipe and end up with a clean, unmanaged phone.
     */
    fun lockAccounts() {
        if (!isDeviceOwner) return
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS) }
            .onFailure { Log.e(TAG, "Could not lock accounts", it) }
    }

    fun unlockAccounts() {
        if (!isDeviceOwner) return
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS) }
            .onFailure { Log.e(TAG, "Could not unlock accounts", it) }
    }

    /**
     * Makes herald the persistent handler for web links.
     *
     * Cosmetic only — enforcement is the DNS filter plus browser allowlisting.
     * This just stops tapped links from bouncing through a disambiguation dialog
     * for a browser that is about to be suspended anyway.
     */
    fun setDefaultBrowser(browserPackage: String = BuildConfig.ALLOWED_BROWSER_PACKAGE) {
        if (!isDeviceOwner) return

        val activity = resolveBrowserActivity(browserPackage) ?: run {
            Log.w(TAG, "Browser $browserPackage is not installed; not setting a default handler")
            return
        }

        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        }

        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, browserPackage)
            dpm.addPersistentPreferredActivity(admin, filter, activity)
        }.onFailure { Log.e(TAG, "Could not set the default browser", it) }
    }

    fun clearDefaultBrowser(browserPackage: String = BuildConfig.ALLOWED_BROWSER_PACKAGE) {
        if (!isDeviceOwner) return
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, browserPackage) }
            .onFailure { Log.e(TAG, "Could not clear the default browser", it) }
    }

    private fun resolveBrowserActivity(browserPackage: String): ComponentName? {
        val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val match = appContext.packageManager
            .queryIntentActivities(probe, 0)
            .firstOrNull { it.activityInfo.packageName == browserPackage }
            ?: return null
        return ComponentName(match.activityInfo.packageName, match.activityInfo.name)
    }

    /**
     * The sanctioned removal path: lifts every restriction and gives up Device
     * Owner, with no wipe and no data loss.
     *
     * Deliberately reverses everything before releasing ownership — after
     * [DevicePolicyManager.clearDeviceOwnerApp] the app has no privilege left to
     * undo anything it set.
     */
    fun releaseDeviceOwnership(): Boolean {
        if (!isDeviceOwner) return false
        return try {
            clearDefaultBrowser()
            disableAlwaysOnVpn()
            unlockAccounts()
            clearUserRestrictions()
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(appContext.packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not release device ownership", e)
            false
        }
    }

    /** Restrictions currently in force, for the status screen. */
    fun activeRestrictions(): List<String> {
        if (!isDeviceOwner) return emptyList()
        val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
        val restrictions = userManager.userRestrictions
        return (MANAGED_RESTRICTIONS + UserManager.DISALLOW_MODIFY_ACCOUNTS)
            .filter { restrictions.getBoolean(it, false) }
    }

    companion object {
        private const val TAG = "DeviceOwnerManager"

        /**
         * DISALLOW_MODIFY_ACCOUNTS is deliberately not here — it is applied
         * separately by [lockAccounts] once the parent's account is in place, so
         * that provisioning does not lock the parent out of adding it.
         */
        val MANAGED_RESTRICTIONS: List<String> = buildList {
            add(UserManager.DISALLOW_CONFIG_VPN)
            add(UserManager.DISALLOW_FACTORY_RESET)
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            add(UserManager.DISALLOW_SAFE_BOOT)
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(UserManager.DISALLOW_USER_SWITCH)
            }
        }
    }
}
