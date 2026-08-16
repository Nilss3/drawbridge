package app.drawbridge.dpc.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.text.format.DateUtils
import android.util.Log
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.apps.BrowserSettings
import app.drawbridge.dpc.apps.InstallLockSettings
import app.drawbridge.dpc.security.ParentKey

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

        // Not while the setup wizard is still running.
        //
        // This used to fire straight from onProfileProvisioningComplete, which
        // meant a QR-provisioned device applied every restriction, brought up an
        // always-on VPN and started a ~470 MiB download from *inside* the
        // wizard. On a Moto G15 on 2026-08-07 that wizard then never finished:
        // Device Owner was granted and the policy compiled, but
        // USER_SETUP_COMPLETE stayed 0, so the phone had no notification shade,
        // Settings closed itself on launch, and the next reboot showed "This
        // phone may be unsafe" and offered a factory reset. Continuing setup
        // from there tore Device Owner back out.
        //
        // The platform hands a DPC an `is_setup_wizard` flag in its provisioning
        // handoffs precisely because it is expected to hold back here. Nothing is
        // lost by waiting: the parent locks the device deliberately, from a
        // screen they have to open anyway, and every path that reaches this
        // method runs again afterwards -- process start, boot, and the lock
        // button itself.
        if (!ProvisioningLog.isSetupComplete(appContext)) {
            ProvisioningLog.record(appContext, "applyManagedDevicePolicy DEFERRED (setup running)")
            return
        }

        ProvisioningLog.record(appContext, "applyManagedDevicePolicy applying")
        applyUserRestrictions()
        applyClockLock()
        enableAlwaysOnVpn(vpnPackage)
        releaseDefaultBrowser()
        updateLockScreenInfo()
    }

    /**
     * Re-asserts the lockdown, but only on a phone that has already been locked.
     *
     * This is what every *automatic* caller wants — process start, boot,
     * provisioning — as opposed to [applyManagedDevicePolicy], which is the
     * deliberate act of locking and is called from the button.
     *
     * The distinction is the whole rule: **nothing is enforced until the parent
     * locks the phone.** Deferring only until the setup wizard finished was not
     * enough, because merely opening drawbridge then applied everything — on a
     * QR-provisioned Moto G15 on 2026-08-07, launching the app once uninstalled
     * Facebook, pulled herald down and switched USB debugging off, none of which
     * anyone had asked for yet.
     *
     * What that window is for: adding the parent's Google account, which is what
     * arms Factory Reset Protection and which the managed setup flow never
     * prompts for; setting a screen lock, likewise skipped; and adb, for anyone
     * bringing up an unfamiliar handset. All three are foreclosed the moment the
     * restrictions land, so they have to happen first.
     *
     * [ParentKey.protectedSince] is the right signal rather than `isLocked`,
     * because it survives unlocking: a parent who unlocks to change a setting has
     * not withdrawn their protection, and the phone should stay locked down while
     * they do it. Only removal clears it.
     */
    fun reapplyIfProtected(vpnPackage: String = appContext.packageName) {
        if (ParentKey(appContext).protectedSince == 0L) {
            ProvisioningLog.record(appContext, "reapply skipped (never locked)")
            return
        }
        applyManagedDevicePolicy(vpnPackage)
    }

    /**
     * What the phone says about itself on the keyguard, to whoever picks it up.
     *
     * Android shows a managed device's own disclosure here, and left to itself it
     * says the phone belongs to an organization — which is wrong on a child's
     * handset in a way that matters: a repair shop, a school or the child reads
     * corporate IT rather than a parent's decision.
     *
     * It also does the only job left standing after Factory Reset Protection
     * turned out not to be armed. A reset cannot currently be prevented, so what
     * remains is noticing one: a phone that has been wiped stops saying this, and
     * a date the parent recognises is a far better thing to miss than a generic
     * notice nobody read in the first place. See docs/handoff.md.
     *
     * Three states, and the distinction between the last two is the point:
     *
     *  - **Never locked** — nothing is enforced yet, so nothing is claimed. The
     *    message is cleared and Android's default comes back. drawbridge does not
     *    say it is guarding a phone it has not started guarding.
     *  - **Locked** — the full line, with the date.
     *  - **Unlocked after having been locked** — still guarding, and truthfully
     *    so: unlocking removes the key, not the restrictions or the filter. The
     *    date comes off because it is the *lock* that is no longer in force.
     *
     * The text is a stored string rather than a resource the system re-resolves,
     * so it does not follow a later language change on its own. Every caller that
     * can change the language or the lock state calls this again.
     */
    fun updateLockScreenInfo() {
        if (!isDeviceOwner) return

        val key = ParentKey(appContext)
        val info = when {
            key.protectedSince == 0L -> null
            key.isLocked && key.lockedSince > 0 -> appContext.getString(
                R.string.lock_screen_info_locked,
                DateUtils.formatDateTime(
                    appContext,
                    key.lockedSince,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or
                        DateUtils.FORMAT_SHOW_TIME,
                ),
            )
            else -> appContext.getString(R.string.lock_screen_info)
        }

        runCatching { dpm.setDeviceOwnerLockScreenInfo(admin, info) }
            .onFailure { Log.e(TAG, "Could not set the lock screen message", it) }
    }

    /** Read back from the platform rather than from what we last wrote. */
    fun lockScreenInfo(): String? =
        if (!isDeviceOwner) null else dpm.deviceOwnerLockScreenInfo?.toString()

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
     * Turns the whole device's internet off, or back on.
     *
     * This is the same lockdown flag [enableAlwaysOnVpn] deliberately leaves
     * off, used on purpose. Permanently on it is a broken phone; for a bounded
     * window it is exactly a curfew — every non-DNS packet dropped, every
     * `connect()` failing with EPERM, in every app.
     *
     * **Calls and SMS are unaffected**, being carrier-side rather than IP, which
     * is what makes this safe to leave running overnight on a child's phone.
     *
     * [allowedPackages] survive the lockdown, for a messaging app that should
     * stay reachable. The allowlist overload landed in API 29; on 28 it is
     * dropped and the curfew is absolute, which is the stricter reading and so
     * the right way to fail.
     *
     * @return true if the requested state was applied.
     */
    fun setNetworkLockdown(
        enabled: Boolean,
        allowedPackages: Set<String> = emptySet(),
        vpnPackage: String = appContext.packageName,
    ): Boolean {
        if (!isDeviceOwner) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dpm.setAlwaysOnVpnPackage(admin, vpnPackage, enabled, allowedPackages)
            } else {
                if (enabled && allowedPackages.isNotEmpty()) {
                    Log.w(TAG, "Lockdown allowlist needs API 29; enforcing an absolute curfew")
                }
                dpm.setAlwaysOnVpnPackage(admin, vpnPackage, enabled)
            }
            Log.i(TAG, "Network lockdown ${if (enabled) "on" else "off"}")
            true
        } catch (e: Exception) {
            // A device whose VPN stack refuses lockdown throws here. Failing to
            // *enter* a curfew is a policy that did not apply; failing to leave
            // one is a bricked phone, so both are logged loudly.
            Log.e(TAG, "Could not set network lockdown to $enabled", e)
            false
        }
    }

    /**
     * Pins the clock. Applied on every locked device, curfew or not.
     *
     * It began as curfew machinery — a wall-clock window is advisory if the
     * clock can be edited — but a movable clock defeats two other things that
     * have nothing to do with curfews:
     *
     *  - **The protected-since date.** Wind the clock back a year, lock, wind it
     *    forward again, and the phone reports a year of continuous protection it
     *    never had. That does not merely weaken the tamper check; it makes it
     *    lie, which is worse than not having one.
     *  - **Anything else the parent layered on top.** Moving the clock is the
     *    standard way round screen-time limits in Family Link and similar tools.
     *    drawbridge cannot enforce those, but it can stop the phone lying to
     *    them.
     *
     * [UserManager.DISALLOW_CONFIG_DATE_TIME] covers date, time *and* time zone.
     * Auto-time is set as well as locked where the API exists (30+), because
     * forbidding edits only freezes whatever the clock already said — a device
     * whose clock was wrong when the restriction landed would stay wrong.
     */
    fun applyClockLock() {
        if (!isDeviceOwner) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setAutoTimeEnabled(admin, true) }
                .onFailure { Log.e(TAG, "Could not force network time", it) }
            runCatching { dpm.setAutoTimeZoneEnabled(admin, true) }
                .onFailure { Log.e(TAG, "Could not force the network time zone", it) }
        }
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
            .onFailure { Log.e(TAG, "Could not lock the clock", it) }
    }

    fun clearClockLock() {
        if (!isDeviceOwner) return
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
            .onFailure { Log.e(TAG, "Could not unlock the clock", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setAutoTimeEnabled(admin, false) }
            runCatching { dpm.setAutoTimeZoneEnabled(admin, false) }
        }
    }

    /**
     * Applies the restriction set, and clears whatever the current state leaves
     * out.
     *
     * Three of these are easy to leave out and each one on its own defeats the
     * whole filter:
     *  - [UserManager.DISALLOW_SAFE_BOOT]: safe mode disables third-party apps,
     *    including the VPN service.
     *  - [UserManager.DISALLOW_ADD_USER] and friends: always-on VPN is per-user,
     *    so a guest profile would get unfiltered network with no restrictions.
     *  - [UserManager.DISALLOW_DEBUGGING_FEATURES]: ADB can clear device owner.
     *
     * The clearing half is what lets a restriction come back *off*. Only
     * USB debugging currently does — see [restrictionsFor] — but the shape is
     * general on purpose: a restriction that can be dropped from the applied set
     * and never removed from the device is the bug [clearRetiredRestrictions]
     * exists to fix, and there is no reason to have two mechanisms for it.
     */
    fun applyUserRestrictions() {
        if (!isDeviceOwner) return
        val wanted = restrictionsToApply()
        wanted.forEach { restriction ->
            runCatching { dpm.addUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not set restriction $restriction", it) }
        }
        (MANAGED_RESTRICTIONS - wanted.toSet()).forEach { restriction ->
            runCatching { dpm.clearUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not clear restriction $restriction", it) }
        }
        clearRetiredRestrictions()
    }

    /**
     * Actively removes restrictions older versions applied and this one does not.
     *
     * Dropping an entry from [MANAGED_RESTRICTIONS] stops it being *set* on new
     * devices and does nothing at all for devices that already carry it — and
     * [DISALLOW_FACTORY_RESET] is the one restriction where that gap is
     * unacceptable, since a phone still carrying it cannot be wiped from
     * recovery. Clearing it here means any device picks up the fix on its next
     * apply, which is every process start on a locked phone.
     */
    private fun clearRetiredRestrictions() {
        RETIRED_RESTRICTIONS.forEach { restriction ->
            runCatching { dpm.clearUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not clear retired restriction $restriction", it) }
        }
    }

    private fun restrictionsToApply(): List<String> {
        val locked = ParentKey(appContext).isLocked
        if (BuildConfig.RETAIN_ADB_ACCESS) {
            Log.w(TAG, "Debug build: leaving USB debugging enabled so adb keeps working")
        } else if (!locked) {
            Log.i(TAG, "Unlocked: leaving USB debugging available to the parent")
        }
        return restrictionsFor(
            isLocked = locked,
            retainAdbAccess = BuildConfig.RETAIN_ADB_ACCESS,
            // Held off while drawbridge is putting an app on the phone itself.
            // See allowOwnInstalls.
            installLock = InstallLockSettings(appContext).isEnabled &&
                InstallLockSettings.ownInstallsInFlight.isEmpty(),
        )
    }

    /**
     * Stands the install restriction down while drawbridge installs something of
     * its own, and is the reason layer 1 can ship without waiting for a handset.
     *
     * **The open question this closes.** `DISALLOW_INSTALL_APPS` is documented as
     * disallowing *a user* from installing apps, and a Device Owner pushing a
     * package is expected to be exempt — that is how every managed enterprise
     * phone works, and Google's own EMM API keeps the two in separate fields. It
     * has not been checked on a handset, and the thing that depends on it is the
     * one that must not break: `required_apps` fetching herald after the browser
     * choice goes from *no browser* back to *the allowed browsers*, and
     * [app.drawbridge.dpc.ui.UpdateActivity] installing a new drawbridge. A
     * phone that cannot do the first has lost its browser permanently, with the
     * key as the only way back.
     *
     * So the restriction comes off for the length of the install rather than
     * being trusted to make an exception. If the exemption does exist this is
     * redundant and costs two no-op platform calls; if it does not, herald still
     * comes back. Either way the answer stops mattering.
     *
     * **It is put back by [app.drawbridge.dpc.update.InstallResultReceiver], not
     * by a `finally`.** The install is asynchronous — `commit` returns long
     * before the package lands — so restoring it at the end of the calling
     * method would re-block the very session it was lifted for. What bounds the
     * window if that broadcast never arrives is that
     * [applyUserRestrictions] recomputes the whole set from scratch on every
     * process start, every boot, every lock and every unlock.
     *
     * The window itself is the honest cost: while drawbridge is downloading and
     * installing an app the parent's policy asked for, a locked phone can install
     * apps. The closed set in [app.drawbridge.dpc.apps.AppBlocker] still removes
     * anything that arrives through it, which is layer 2 doing exactly the job it
     * exists for.
     */
    fun allowOwnInstalls(packageName: String) {
        InstallLockSettings.beginOwnInstall(packageName)
        applyUserRestrictions()
    }

    /** Ends the window [allowOwnInstalls] opened, whatever the install's verdict. */
    fun ownInstallFinished(packageName: String) {
        InstallLockSettings.endOwnInstall(packageName)
        applyUserRestrictions()
    }

    fun clearUserRestrictions() {
        if (!isDeviceOwner) return
        (MANAGED_RESTRICTIONS + RETIRED_RESTRICTIONS).forEach { restriction ->
            runCatching { dpm.clearUserRestriction(admin, restriction) }
                .onFailure { Log.e(TAG, "Could not clear restriction $restriction", it) }
        }
    }

    /**
     * Opens account changes again, on the way out.
     *
     * Redundant with [clearUserRestrictions], which now covers this restriction
     * too — and kept anyway, because teardown order is load-bearing here and an
     * explicit, ordered step is cheaper than reasoning about whether the general
     * one still catches it. There is no matching `lockAccounts()`: applying it is
     * [restrictionsFor]'s job, keyed on the lock.
     */
    fun unlockAccounts() {
        if (!isDeviceOwner) return
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS) }
            .onFailure { Log.e(TAG, "Could not unlock accounts", it) }
    }

    /**
     * Makes sure drawbridge is not holding the web-link default, so Android's
     * own default-app machinery decides it.
     *
     * **drawbridge used to pin herald here, and stopped on 2026-08-15.** The
     * intention was only ever "herald is the recommendation" — but the sole
     * Device Owner API for a default handler,
     * `addPersistentPreferredActivity`, is documented to keep its activity as
     * the default *"even if the intent preferences are reset"*. It is built to
     * be un-overridable, which is the right tool for a kiosk and the wrong one
     * for a recommendation: a parent who preferred Chrome for links could not
     * say so anywhere on the phone.
     *
     * Working around that meant drawbridge growing its own default-browser
     * picker, which is a second answer to a question Android already asks well.
     * So it does not pin, and the platform behaves normally: the first tapped
     * link brings up the chooser with every allowed browser in it, *always*
     * makes one the default, and Settings → Default apps changes it later.
     *
     * This still runs on every policy application, because a phone updating from
     * a build that *did* pin is carrying a preference nothing else will ever
     * clear. Clearing it is cheap and idempotent; leaving it would strand those
     * devices on a default they could not change, which is the whole complaint.
     */
    fun releaseDefaultBrowser() {
        if (!isDeviceOwner) return

        val policy = DrawbridgeApplication.policy(appContext).policy.value
        (policy.browserPackages + BuildConfig.ALLOWED_BROWSER_PACKAGE)
            .distinct()
            .forEach { clearDefaultBrowser(it) }
    }

    /** Drops drawbridge's claim on web links for one package. */
    fun clearDefaultBrowser(browserPackage: String = BuildConfig.ALLOWED_BROWSER_PACKAGE) {
        if (!isDeviceOwner) return
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, browserPackage) }
            .onFailure { Log.e(TAG, "Could not clear the default browser", it) }
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
            // Clears lockdown with it, by dropping the always-on package
            // entirely. A removal that left lockdown set would hand back a phone
            // with no internet and nothing left on it able to undo that.
            disableAlwaysOnVpn()
            unlockAccounts()
            clearUserRestrictions()
            clearClockLock()
            // Before ownership goes, like everything else here: this is a Device
            // Owner call, and a phone handed back still claiming to be guarded
            // would be the same class of lie the message exists to prevent.
            runCatching { dpm.setDeviceOwnerLockScreenInfo(admin, null) }
                .onFailure { Log.e(TAG, "Could not clear the lock screen message", it) }
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
        return (
            MANAGED_RESTRICTIONS +
                RETIRED_RESTRICTIONS +
                UserManager.DISALLOW_MODIFY_ACCOUNTS +
                UserManager.DISALLOW_CONFIG_DATE_TIME
            ).distinct().filter { restrictions.getBoolean(it, false) }
    }

    companion object {
        private const val TAG = "DeviceOwnerManager"

        /**
         * The restriction set for a given state, and the only place the
         * USB-debugging rule lives.
         *
         * **USB debugging follows the lock, not the protection.** Every other
         * restriction here is keyed on [ParentKey.protectedSince] through
         * [reapplyIfProtected], which survives unlocking on purpose: a parent
         * changing a setting has not withdrawn their protection, and the phone
         * should stay filtered while they do it. This one is deliberately
         * different, and the reason is that it is the project's only working
         * delivery channel.
         *
         * **Adding online accounts is deliberately *not* restricted.**
         * `DISALLOW_MODIFY_ACCOUNTS` was wired up here on 2026-08-10 and taken
         * straight back out: people legitimately carry several accounts, and
         * blocking the lot to stop one is the wrong trade. It also blocked
         * *removing* accounts, so any app signing in through `AccountManager`
         * became unusable on a locked phone. What actually matters is
         * [UserManager.DISALLOW_ADD_USER], which is unconditional and stops a
         * second user profile — the one that would get unfiltered network,
         * since always-on VPN is per-user.
         *
         * Play Protect refuses to install `app.drawbridge.dpc`, so a phone
         * cannot update drawbridge by itself and cannot be provisioned by QR.
         * What does work is a cable — see `tools/provision-adb.sh`. Applying
         * this restriction for the life of the device means the cable is
         * available exactly once, before the first lock, and a phone in the
         * field can then never be fixed at all.
         *
         * Gating it on the *lock* costs nothing that was not already given away.
         * An unlocked drawbridge is a drawbridge whose configuration screen is
         * open, and that screen offers complete removal in its overflow menu —
         * so somebody holding the key can already undo everything, with or
         * without adb. The restriction only ever protected against someone who
         * does *not* have the key, and that person cannot unlock the phone to
         * begin with.
         *
         * Note this does not switch USB debugging *on*. It stops the platform
         * refusing it; the developer options toggle is still a deliberate act.
         *
         * **[UserManager.DISALLOW_INSTALL_APPS] is the second conditional
         * entry**, and it is keyed on the lock for the same reason rather than
         * on protection: installing something is the main thing a parent unlocks
         * the phone *to do*. It also takes a third condition, the household's
         * own [app.drawbridge.dpc.apps.InstallLockSettings] switch, because
         * unlike everything else here it is off by default — it changes what the
         * phone is rather than what it filters, so nobody gets it by leaving a
         * button unpressed.
         *
         * This is prevention, and it is deliberately not the whole feature.
         * Whether the platform lets Play Store *updates* through it is
         * unverified on real hardware, so the promise — no new apps, updates
         * still arriving — is carried by the closed set in
         * [app.drawbridge.dpc.apps.AppBlocker] whatever this restriction turns
         * out to do. See docs/handoff.md.
         *
         * There is no default for [installLock] on purpose. A safety feature
         * that switches itself off when a caller forgets an argument is the kind
         * of silence this codebase has paid for before.
         */
        fun restrictionsFor(
            isLocked: Boolean,
            retainAdbAccess: Boolean,
            installLock: Boolean,
        ): List<String> {
            val withheld = buildSet {
                if (!isLocked || retainAdbAccess) add(UserManager.DISALLOW_DEBUGGING_FEATURES)
                if (!isLocked || !installLock) add(UserManager.DISALLOW_INSTALL_APPS)
            }
            return MANAGED_RESTRICTIONS - withheld
        }

        val MANAGED_RESTRICTIONS: List<String> = buildList {
            add(UserManager.DISALLOW_CONFIG_VPN)
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            // Conditional, like debugging above it — see restrictionsFor. It has
            // to be in this list all the same, because applyUserRestrictions
            // computes what to *clear* from it: a conditional restriction
            // outside this list would be applied once and never come off.
            add(UserManager.DISALLOW_INSTALL_APPS)
            add(UserManager.DISALLOW_SAFE_BOOT)
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(UserManager.DISALLOW_USER_SWITCH)
            }
            // Private DNS is reachable from Settings even with DISALLOW_CONFIG_VPN
            // set, because Android files it under network rather than VPN
            // settings. Measured on a Moto G15 (Android 15) on 2026-08-07:
            // pointing it at a public DoT resolver does *not* leak past the
            // filter — strict mode cannot complete its handshake through a
            // tunnel that routes only port 53, so resolution fails closed and
            // blocked names stay blocked. Opportunistic mode leaves the filter
            // intact too.
            //
            // It is still worth taking away. Failing closed means every name on
            // the phone stops resolving, so the switch is a one-tap self-inflicted
            // outage that looks exactly like drawbridge being broken — and the
            // fail-closed behaviour is a consequence of the tunnel's routing
            // rather than a guarantee anyone wrote down, so it is not something
            // to keep depending on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        }

        /**
         * Applied by earlier versions, and now removed on sight.
         *
         * [UserManager.DISALLOW_FACTORY_RESET] does far more than its name and
         * documentation suggest. It does not merely hide the entry in Settings:
         * measured on a Moto G15 on 2026-08-07, it strips "Wipe data/factory
         * reset" out of the **hardware recovery menu** as well, and the entry
         * reappears the moment drawbridge gives up Device Owner. A phone whose
         * key is lost is then reclaimable only by reflashing firmware from a PC.
         *
         * That is not a price a parent should pay for mislaying a piece of
         * paper, so drawbridge no longer sets it. What holds the line instead is
         * Factory Reset Protection, which is why
         * [provisioning](../../../../../../../docs/provisioning.md) asks for the
         * parent's Google account and only theirs, and the protected-since date,
         * which makes a reset visible after the fact.
         */
        val RETIRED_RESTRICTIONS: List<String> = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
        )
    }
}
