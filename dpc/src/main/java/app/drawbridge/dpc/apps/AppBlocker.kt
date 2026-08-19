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
import app.drawbridge.dpc.apps.store.StoreCatalogue
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.policy.model.AppRatings
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
 * And two more that do not come from a list at all:
 *
 *  - **what the store says** — a game, a dating app, or anything rated above
 *    what the policy allows. This is the rule that goes upstream of the
 *    blocklist, because new apps in a category appear faster than a signed
 *    document can name them. See [AppRatings] and [StoreCatalogue];
 *  - **with the install lock on**, anything outside the set of packages the
 *    phone carried at the last lock. See [InstallLockSettings].
 *
 * Browsers are *detected*, not listed: a package-name list of browsers is out of
 * date the moment someone publishes a new one, whereas the intent filter that
 * makes an app able to open `https://` links is what actually matters.
 *
 * **When this starts, as of 2026-08-17: at installation.** An app that no switch
 * on the configuration screen can bring back — on the blocklist, a browser the
 * *policy* never sanctioned, rated or categorised out by the store — goes as soon
 * as drawbridge is on the phone and has read a policy. It no longer waits for the
 * first lock.
 *
 * The reason is the owner's, and it is about who drawbridge is for: **not
 * everybody is going to lock.** A phone that filters the web and drops social
 * media, undoable by a factory reset and nothing else, is already most of the
 * value, and a version of that which quietly does nothing until a button is
 * pressed is a version that fails the person who never presses it.
 *
 * What still waits for the lock is everything a switch still governs — WhatsApp,
 * Telegram, YouTube, streaming, a browser the *chooser* narrowed away — because
 * the parent has not answered those questions yet. See [actsNow].
 *
 * **The cost is real and lands before anybody has agreed to anything**: on a
 * phone already in use, apps start disappearing minutes after installation, and
 * whatever was in them goes too. That is why the warning moved to *before the
 * install* rather than before the lock — see the install pages, which now say it
 * ahead of the cable rather than beside the button.
 */
class AppBlocker(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = DrawbridgeDeviceAdminReceiver.componentName(appContext)
    private val parentKey = ParentKey(appContext)
    private val installLock = InstallLockSettings(appContext)
    private val storeCatalogue = StoreCatalogue(appContext)

    /**
     * What was done to a package, which is not the same question as whether it
     * worked.
     *
     * [HIDDEN] and [SUSPENDED] used to be one value, named `SUSPENDED` and
     * meaning *hidden* — harmless while hiding was the only mechanism, and
     * actively misleading from the moment there were two. The log line these end
     * up in exists to tell a phone's branches apart, so the two mechanisms have
     * to be distinguishable in it. [FAILED] means the package is still on the
     * phone and still openable, which is the state [standings] reports.
     */
    enum class Action { NONE, UNINSTALLED, HIDDEN, SUSPENDED, FAILED }

    /** What became of a package the policy disallows. See [standings]. */
    enum class Standing { GONE, HIDDEN, SUSPENDED, PRESENT }

    /**
     * Applies policy to a single package, e.g. one that has just been installed.
     *
     * **Removal follows the lock**, which is the single gate every path into this
     * class goes through — the install receiver, the periodic sweep, and the
     * configuration screen alike.
     *
     * It used to follow nothing at all. [PackageWatcher] lives inside
     * [DnsFilterService], the filter deliberately keeps running after the parent
     * unlocks, and so removals kept happening on an unlocked phone: an app
     * installed to move data off the device was uninstalled seconds later, and a
     * second browser could not be kept long enough to try it. The one gate that
     * did exist was on the configuration screen and asked
     * `protectedSince != 0` — *has ever been locked* — which stays true forever
     * once the phone has been locked once, so unlocking never reopened anything.
     *
     * Keying it on the lock gives nothing away, for the same reason USB debugging
     * is keyed there (see [DeviceOwnerManager.restrictionsFor]): unlocking costs
     * the parent's key, and an unlocked drawbridge already offers complete
     * removal from its own overflow menu. Whoever can reach this state can undo
     * everything anyway.
     *
     * **What it does cost, and it should be said plainly.** Every other browser
     * is removed because a browser carrying its own encrypted DNS routes around
     * a DNS-only filter. While the phone is unlocked that protection is off, so
     * an unlocked phone with a second browser on it is filtered less than it
     * looks. Re-locking sweeps it away again — [LockActivity.sealWithKey] runs a
     * full sweep the moment the key is committed.
     */
    fun evaluate(packageName: String): Action {
        val policy = DrawbridgeApplication.policy(appContext).policy.value
        val allowedBrowsers = allowedBrowsers(policy)

        // **The install lock is asked before the fast path, not after it.** It is
        // the one rule here that is not a judgement about what an app *is* — see
        // [reasonToRemove] — so a package every content rule would keep can still
        // be one this phone will not accept today.
        val newcomer = outsideTheInstalledSet(packageName)
        if (!newcomer && isProtected(packageName, policy, allowedBrowsers)) return Action.NONE

        val browser = isBrowser(packageName)
        val removal = reasonToRemove(packageName, policy, browser, newcomer) ?: return Action.NONE

        // **Only what a control on the configuration screen could still change
        // waits for the lock.** Everything else goes as soon as it is seen. See
        // [deferred] for which is which and why; the short version is that a
        // browser the *policy* never sanctioned is a way around a DNS-only
        // filter and goes in either state, while one the *parent* narrowed away
        // with the browser chooser is a reversible preference and waits.
        //
        // The window before the *first* lock is untouched, and is checked above:
        // it is what lets a parent move their data across, and it is the reason
        // drawbridge does not need a factory reset.
        if (!actsNow(removal.waitsForLock, parentKey.isLocked)) return Action.NONE

        Log.i(TAG, "Removing $packageName: ${removal.reason}")
        return remove(packageName, reversible = removal.reversible)
    }

    /**
     * Why policy will not have this package, or null if policy is content with
     * it. The reason is a log line, so it reads as one.
     */
    private fun reasonToRemove(
        packageName: String,
        policy: Policy,
        browser: Boolean,
        newcomer: Boolean,
        allowedBrowsers: Set<String> = allowedBrowsers(policy),
    ): Removal? {
        // **The install lock outranks every other rule here, including
        // [isProtected], and that is the fix for a bug the Moto found on
        // 2026-08-17.** With the option on and the phone locked, Claude, DeepSeek
        // and Session installed and stayed — they are on the whitelist — and so
        // did Telegram, which an option allows. All four were wrong for the same
        // reason: those rules answer *is this app acceptable*, and this one
        // answers *did this phone have it when it was sealed*. "Only the apps
        // already on this phone" cannot have exceptions and still mean anything.
        //
        // Nothing drawbridge installs itself is caught by this, and not because
        // it is exempt: [InstallLockSettings.allow] puts a package into the set
        // before the session is committed, and `closeTheInstalledSet` counts one
        // still downloading as present. herald reappearing after a browser-policy
        // change survives on those two mechanisms rather than on a bypass, which
        // is why removing the bypass costs it nothing.
        val newcomerRemoval = Removal(
            "not among the apps this phone had when it was locked",
            // Being new is the one reason that waits, and only while the
            // install lock is on. With it off — the default — a locked phone
            // installs whatever the policy allows, and this rule never fires at
            // all. With it on, unlocking is the only route a *person* has, so
            // removing an app there would take away exactly what the parent
            // unlocked the phone to install.
            waitsForLock = true,
        ).takeIf { newcomer }

        if (isProtected(packageName, policy, allowedBrowsers)) return newcomerRemoval

        // Whether a *switch on the configuration screen* still governs this
        // package. It is asked per reason rather than per package, which is the
        // fix for the second Moto report of 2026-08-17 — see [Removal].
        val switchGoverned = deferred(packageName, policy, allowedBrowsers)

        return when {
            packageName in policy.blockedPackages ->
                Removal("on the blocked package list", switchGoverned, reversible = switchGoverned)
            // Gated on the document having been read — see [browserRuleApplies],
            // which is the one branch here that has to ask.
            //
            // The *effective* set rather than the policy's, so the log line says
            // what this phone actually allows — "allows only nothing" is the
            // honest reading of the no-browser choice, and was worth not hiding.
            browserRuleApplies(browser, policy.version) -> Removal(
                "is a browser, and this phone allows only " +
                    allowedBrowsers.joinToString().ifEmpty { "no browser at all" },
                switchGoverned,
                reversible = switchGoverned,
            )
            notAllowed(packageName, policy) ->
                Removal("not on this profile's allowed list", waitsForLock = false)
            // The store's answer, after everything the phone can decide for
            // itself. Deliberately below the blocklist: an app the policy names
            // has already been decided on, and asking Play about it would be a
            // network round trip to reach the same answer more slowly.
            //
            // The install lock is last of all, because every other branch says
            // *why* an app is unwelcome and this one only says it is new: a
            // package that is both on the blocklist and outside the set should
            // log the blocklist. That is about the log line, not about
            // precedence — a protected package has already been checked against
            // it above, which is where the outranking happens.
            else -> storeReason(packageName, policy)
                ?.let { Removal(it, waitsForLock = false) }
                ?: newcomerRemoval
        }
    }

    /**
     * Why a package goes, and whether that reason waits for the lock.
     *
     * **The two used to be decided separately, and that was a bug the Moto found
     * on 2026-08-17.** Deferral was asked about the *package* — is it
     * option-governed, is it a narrowed-away browser, is it outside the install
     * lock's set — and a newly installed package is outside that set by
     * definition. So with the install lock on, every app installed during an
     * unlock was deferred, whatever else was wrong with it: TikTok, Firefox and
     * Temu all stayed on an unlocked phone that should have removed all three on
     * sight.
     *
     * Instagram is what gave it away by behaving correctly. It was on the phone
     * at the previous lock, so it was *in* the set, so it was not a newcomer, so
     * nothing deferred it and the blocklist removed it immediately. One app out
     * of five doing the right thing is a better clue than five doing the wrong
     * one.
     *
     * The rule now travels with the reason. Being **new** waits for the lock,
     * because with the install lock on, unlocking is the only route a person has
     * to add something. Being **blocked by name** or **an unsanctioned browser**
     * or **rated out** does not, and the install lock cannot rescue it — an app
     * can be new *and* disallowed, and the disallowed half is the one that
     * decides.
     *
     * Crunchyroll is the case that shows the rule is not simply "act always": it
     * is on the blocklist *and* covered by the streaming option, so it waits for
     * the lock like everything a switch still governs. That was correct
     * behaviour in the same report.
     */
    private data class Removal(
        val reason: String,
        val waitsForLock: Boolean,
        /**
         * Whether this removal is one a switch can undo, and must therefore keep
         * the app's data.
         *
         * **The same set as [waitsForLock] for the two rules a control governs,
         * and deliberately not the same field.** A newcomer under the install
         * lock also waits for the lock, and that one is not reversible: "no other
         * apps" is a decision about what the phone carries, not a preference
         * somebody flips back and forth, and an app kept on disk in case the
         * install lock is ever switched off would be a phone quietly full of
         * things it says it does not have.
         *
         * What this covers is exactly what a parent can put back from the
         * configuration screen: WhatsApp and Telegram under their options, and a
         * browser the chooser narrowed away. Those are hidden rather than
         * uninstalled, so the chats, the bookmarks and the logins are all still
         * there when the switch goes the other way. See [remove].
         */
        val reversible: Boolean = false,
    )

    /**
     * What the store says, or null if it has nothing to say about this package.
     *
     * **Three gates before the cache is even asked**, and each is load-bearing:
     *
     *  - **the policy has to carry the rule.** `app_ratings` is absent from every
     *    document older than policy 62, and a phone polling one of those must
     *    behave exactly as it did before;
     *  - **the rule has to be configured.** An empty `allowed_ratings` cannot
     *    express *these pass*, and reading it as *nothing passes* would remove
     *    every app on the phone — see [AppRatings.isConfigured];
     *  - **the store has to be able to reach the package** — see
     *    [withinStoreReach]. Every user-installed app, and a preinstalled one
     *    only if it has a launcher icon.
     *
     * Then the cache, which never blocks on the network — see [StoreCatalogue].
     * A package nobody has fetched yet is [AppRatings.Verdict.UNVERIFIED], which
     * is *keep*, so the rule stays silent until something has actually asked.
     */
    private fun storeReason(packageName: String, policy: Policy): String? {
        val ratings = policy.appRatings ?: return null
        if (!ratings.isConfigured) return null
        if (!withinStoreReach(packageName)) return null

        if (storeCatalogue.verdictFor(packageName, ratings) != AppRatings.Verdict.BLOCKED) {
            return null
        }

        // Say which half fired. "the store disagrees" is the log line that cost
        // 2026-08-14 an evening in a different part of this class.
        val answer = storeCatalogue.answerFor(packageName)
        val category = answer?.category
        return if (category != null &&
            ratings.verdict(rating = "", category = category) == AppRatings.Verdict.BLOCKED
        ) {
            "the store files it under $category"
        } else {
            "the store rates it ${answer?.rating ?: "outside what this phone allows"}"
        }
    }

    /**
     * Fetches the store's answer for one package, if it is wanted and not
     * already current. Blocking; call it off the main thread.
     *
     * **Separate from [evaluate] on purpose.** Evaluation runs from a broadcast
     * receiver and from a sweep over every package on the device, and it must
     * never wait on a network. This is what a caller reacting to a single
     * install does first, so that the evaluation a moment later has something to
     * read. The sweep does not call it — a full pass is 50–100 MB and belongs on
     * an unmetered network, deliberately, rather than every fifteen minutes.
     */
    /**
     * The packages a store scan still has to ask about: user-installed, not
     * already spoken for by an earlier branch, and not already answered.
     *
     * The same four gates [ensureStoreAnswer] applies, in one place, because two
     * callers need the answer and they must not drift: the worker that does the
     * scanning and the Diagnostics line that reports how much of it is left. A
     * screen that counted differently from the job would be a screen reporting
     * progress on something else.
     *
     * **This is what makes the scan affordable**, and the launcher-icon rule in
     * [withinStoreReach] is what keeps it affordable now that preloads are in
     * scope. A handset carries ~294 packages, of which the great majority are
     * framework and OEM services nobody can open: those have no launcher entry,
     * so they are never asked about. What is left is the user-installed apps plus
     * the preloads a person can actually tap, minus everything protected and
     * everything already cached. At ~1.2 MB a listing, that difference is the
     * difference between 350 MB and something a phone can do on Wi-Fi.
     */
    fun packagesWantingStoreAnswer(): List<String> {
        val policy = DrawbridgeApplication.policy(appContext).policy.value
        val ratings = policy.appRatings ?: return emptyList()
        if (!ratings.isConfigured) return emptyList()

        val allowedBrowsers = allowedBrowsers(policy)
        return packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .filter { withinStoreReach(it) }
            .filterNot { isProtected(it, policy, allowedBrowsers) }
            .filterNot { storeCatalogue.isFresh(it) }
            .sorted()
    }

    fun ensureStoreAnswer(packageName: String) {
        val policy = DrawbridgeApplication.policy(appContext).policy.value
        val ratings = policy.appRatings ?: return
        if (!ratings.isConfigured) return
        if (!withinStoreReach(packageName)) return
        if (isProtected(packageName, policy, allowedBrowsers(policy))) return
        if (storeCatalogue.isFresh(packageName)) return
        storeCatalogue.fetch(packageName, ratings)
    }

    /**
     * Whether the store rule is allowed to have an opinion about this package.
     *
     * **Preinstalled apps used to be exempt outright, and stopped being on
     * 2026-08-17, because a Moto G15 kept a preloaded game called Amaze GO!
     * (`com.oakever.arrows`, which Play files under `GAME_PUZZLE`).** The rule
     * had the right answer and was never asked the question: the game arrived
     * from the factory rather than from the Play Store, so all of it — the
     * verdict, the scan, the diagnostics count — skipped it. On a handset whose
     * junk is preloaded, that exempted precisely the apps somebody bought this
     * project to be rid of, and naming them one at a time in `blocked_packages`
     * is the treadmill the store rule exists to end.
     *
     * **The launcher icon is what replaces the exemption.** The old reasoning was
     * sound about its own example — hiding the OEM's dialer or a framework
     * service because Play has no listing for it would leave an unusable phone —
     * but it proved too much. Two rails already answer it:
     *
     *  - **no listing means keep.** A package Play has never heard of is
     *    [AppRatings.Verdict.UNVERIFIED], which this rule treats as *keep*. The
     *    dialer was never at risk from a missing listing; it was at risk from a
     *    rule that fails closed, and this one fails open.
     *  - **[isProtected] still runs first**, so the launcher, the keyboard,
     *    Settings, Play and the rest of [NEVER_TOUCH] are not candidates at all.
     *
     * What the icon adds is scale rather than safety: the ~294 packages on a
     * handset are mostly services with no way to open them, and asking Play about
     * every one would multiply the scan sevenfold to learn nothing. A preloaded
     * *game* always has an icon, because being tapped is the whole point of it.
     */
    private fun withinStoreReach(packageName: String): Boolean = withinStoreReach(
        isPreinstalled = isSystemPackage(packageName),
        hasLauncherEntry = hasLauncherEntry(packageName),
    )

    /**
     * Whether anything on this phone can open [packageName] from the home screen.
     *
     * **Queried with the two `MATCH_` flags rather than through
     * `getLaunchIntentForPackage`, and that is not a detail.** A hidden package
     * answers no ordinary intent query — the same property [browsersOnDevice]
     * documents — so the simple call would report *no icon* for exactly the apps
     * this rule has already removed. The verdict would then flip to *keep* on the
     * next sweep, [disallows] would stop naming it, and Diagnostics' `still
     * usable` line would lose the one package it was meant to explain.
     */
    private fun hasLauncherEntry(packageName: String): Boolean = runCatching {
        val probe = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        packageManager.queryIntentActivities(
            probe,
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES,
        ).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Whether the install lock has anything to say about this package, read off
     * this phone.
     *
     * [InstallLockSettings.outsideTheSet] is where the rule lives; this is the
     * reads that feed it. Deliberately *not* consulted for the lock state — see
     * [deferred], which is where "now or at the lock" is decided for every rule
     * in this class.
     *
     * **Limited to user-installed apps, for the same reason [notAllowed] is.**
     * This is the second rule in the class that removes what is *not* named
     * rather than what is, and that is the shape that can take a phone apart. A
     * snapshot is worse than an allowlist here rather than better: it is
     * generated rather than written, so nobody ever reads it, and it cannot know
     * about a package that does not exist yet. An Android version upgrade
     * legitimately adds system apps — a snapshot taken on Android 15 has never
     * heard of what 16 ships — and hiding those would be an OTA quietly
     * subtracting from the phone, with `restoreNowAllowed` unable to bring them
     * back because it only restores what the *policy* names.
     *
     * Nothing is lost by it. This setting exists because of the Play Store: the
     * AI companion apps that produced policy 59 are user installs to a package,
     * and a preinstalled app was on the phone when the parent locked it. What
     * stays available for those is `blocked_packages`, which hides rather than
     * uninstalls and is therefore reversible.
     */
    private fun outsideTheInstalledSet(packageName: String): Boolean =
        !isSystemPackage(packageName) &&
            InstallLockSettings.outsideTheSet(
                enabled = installLock.isEnabled,
                snapshot = installLock.snapshot,
                packageName = packageName,
            )

    /**
     * Closes the set: records what the phone carries, at the moment it becomes
     * locked.
     *
     * **Visible packages only, not `MATCH_UNINSTALLED_PACKAGES`.** The set means
     * *the apps this phone can open right now*, and the wider query also returns
     * packages that were uninstalled with their data kept — so using it would
     * quietly sanction reinstalling anything that had ever been on the device,
     * including whatever the parent removed on purpose. The narrower query's own
     * blind spot, a package drawbridge has *hidden*, costs nothing: hiding is
     * only ever undone by [restoreNowAllowed], which restores exactly the
     * packages the policy names as allowed or exempt, and [isProtected] declines
     * those before the install-lock branch is ever reached.
     *
     * Called from [app.drawbridge.dpc.DrawbridgeApplication.sweepOnLock], which
     * runs it before the sweep it is named for. That order is the whole reason
     * the unlock window works as a way to add an app.
     *
     * **Not gated on the switch**, deliberately. Gating it would leave a phone
     * that locked with the install lock off carrying a snapshot from whenever it
     * was last on — months of drift, ready to be believed the moment somebody
     * flips the switch back. Recording it every time costs one package
     * enumeration on a code path that is about to enumerate them anyway, and it
     * means the set is never older than the lock.
     */
    fun closeTheInstalledSet() {
        // Plus whatever drawbridge is installing at this moment, which is not on
        // the phone yet and must not be treated as absent. herald is over 200 MB,
        // so a parent who chooses *the allowed browsers* and then presses Lock
        // reaches here while the download is still running — and a set recorded
        // without it is drawbridge sweeping away the browser it just fetched.
        // See InstallLockSettings.ownInstallsInFlight.
        val installed = packageManager.getInstalledApplications(0).map { it.packageName } +
            InstallLockSettings.ownInstallsInFlight
        installLock.take(installed)
        Log.i(TAG, "Install lock: sealed the phone with ${installed.toSet().size} packages on it")
    }

    /**
     * The same question [evaluate] asks, without doing anything about it.
     *
     * Exists for [PackageRemovalReceiver], which learns that an uninstall was
     * refused and has to decide whether to fall back to hiding — and must not
     * call [evaluate] to find out, because for a user-installed app that would
     * issue a second uninstall, be refused again, and come straight back here.
     * A separate copy of the rule in the receiver was the other option, and the
     * split-brain rules this project has already paid for say not to.
     *
     * Deliberately not gated on the lock: the caller is reacting to a removal
     * that was already decided on and started, not starting a new one.
     */
    fun disallows(packageName: String): Boolean {
        val policy = DrawbridgeApplication.policy(appContext).policy.value
        return reasonToRemove(
            packageName,
            policy,
            isBrowser(packageName),
            outsideTheInstalledSet(packageName),
        ) != null
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
        val actions = installed
            .associate { it.packageName to evaluate(it.packageName) }
            .filterValues { it != Action.NONE }
        restoreNowAllowed()
        return actions
    }

    /**
     * What actually became of every package the policy disallows — read off the
     * phone, not remembered from when it was done.
     *
     * **This is the line that was missing on 2026-08-14.** The owner reported
     * that the YouTube app was still on the phone after the option was switched
     * off and the phone locked, and nothing on the device could say whether the
     * rule had declined it, the platform had refused it, or something had put it
     * back. That took a build with new logging, an adb cable and an evening.
     * [Standing.PRESENT] answers it at a glance and needs neither.
     *
     * **Live rather than a record of the last sweep**, which matters because the
     * only screen this can be read from is the configuration screen, and that
     * screen only exists while the phone is unlocked. A stored sweep result
     * would be overwritten with "nothing to do" by the first sweep after the
     * unlock — [evaluate] declines everything in that state — and would report
     * the emptiness rather than the phone. Unlocking does not un-hide or
     * un-suspend anything, so reading the state directly stays true in both.
     *
     * Its one blind spot is an app installed *during* an unlock, which reads as
     * [Standing.PRESENT] and is not a failure: it goes at the next lock.
     * Allowlist mode is deliberately not covered — it would list every ordinary
     * app on the phone and bury the handful of lines worth reading.
     */
    fun standings(): Map<String, Standing> {
        val policy = DrawbridgeApplication.policy(appContext).policy.value

        // Hidden packages are absent from an ordinary query and present in this
        // one, which is the whole distinction between GONE and HIDDEN below.
        val known = packageManager
            .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .mapTo(mutableSetOf()) { it.packageName }
        val visible = packageManager
            .getInstalledApplications(0)
            .mapTo(mutableSetOf()) { it.packageName }

        // Browsers are included because a browser that survives removal is not
        // one more app left behind: the filter is DNS-only, so it is the filter
        // switched off on a phone still claiming to be protected.
        val candidates = (policy.blockedPackages + browsersOnDevice())
            .distinct()
            .filterNot { isProtected(it, policy, allowedBrowsers(policy)) }

        return candidates.associateWith { packageName ->
            when {
                packageName !in known -> Standing.GONE
                // Absent from the visible list is either hidden or uninstalled
                // with its data kept, and only the first is drawbridge's doing.
                packageName !in visible ->
                    if (isHidden(packageName)) Standing.HIDDEN else Standing.GONE
                isSuspended(packageName) -> Standing.SUSPENDED
                else -> Standing.PRESENT
            }
        }.toSortedMap()
    }

    /**
     * Brings back an app the policy has started allowing.
     *
     * Hiding is how a *preinstalled* app is removed — Chrome and the OEM's own
     * browser cannot be uninstalled — and until now nothing ever reversed it
     * except [unhideAll] during complete removal. So a policy that added Chrome
     * to `allowed_browser_packages` left every phone that had already hidden it
     * hidden forever, with no way back short of taking drawbridge off the device.
     *
     * **Deliberately restricted to what the policy names explicitly**: the
     * allowed browsers and the exempt packages. The tempting general rule — unhide whatever would no longer be
     * removed — cannot be written safely, because [isBrowser] asks the package
     * manager which apps answer an `https://` intent and a hidden app answers
     * nothing. Unhiding a browser to find out whether it is one would hide it
     * again on the next sweep, every fifteen minutes, forever.
     *
     * The cost of that restriction: a *preinstalled* app that stops being in
     * `blocked_packages` is not restored automatically. Removing drawbridge
     * still restores everything.
     *
     * **There is no subtraction of `blocked_packages`, and there was.** That is
     * the bug the owner found on 2026-08-13: switching *Allow YouTube* on left a
     * hidden YouTube hidden through a lock, an unlock and a reboot. An option
     * exempts a package **without** taking it off the blocked list — that is the
     * shape of every option this policy has, WhatsApp included — so subtracting
     * the blocked list removed precisely what the option had just allowed. The
     * restore could only ever have worked for Chrome, which is allowed and
     * happens not to be blocked by name.
     *
     * `isProtected` is the authority everywhere else: [evaluate] consults it
     * *before* the blocked-list branch, so exempt already beats blocked. This has
     * to agree with it or the two disagree about the same package.
     *
     * **Public, and called outside a sweep on purpose.** Until 2026-08-13 this
     * ran only from [sweep], which the configuration screen skips while the
     * phone is unlocked — so switching *Allow YouTube* on did nothing visible
     * and the hidden app stayed hidden until the next reboot or lock. Restoring
     * is additive, like installing herald and like starting the filter, so it
     * belongs with the things that do not wait for the lock.
     */
    fun restoreNowAllowed() {
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        val policy = DrawbridgeApplication.policy(appContext).policy.value
        for (packageName in restorable(policy, allowedBrowsers(policy))) restore(packageName)
    }

    /**
     * Undoes both rungs of [hideOrSuspend], and does not let either failure take
     * the other down with it.
     *
     * **A restore has to cover every mechanism removal has**, or switching an
     * option back on brings back the apps that happened to hide and leaves the
     * ones that had to be suspended sitting in the launcher refusing to open —
     * which is the same bug as the one this fallback exists to fix, pointed the
     * other way. That half is the one that can strand a phone, so it is the half
     * worth being careful about.
     *
     * The two halves are caught separately on purpose. They were one `runCatching`
     * over both reads and both writes, which meant a package the *first* read
     * threw on — a name the policy carries that this phone has never had, say —
     * never reached the second and stayed suspended with nothing left to
     * un-suspend it.
     */
    private fun restore(packageName: String) {
        // **Ask nothing about a package this phone has never had.** The policy
        // names every browser and every exempt package it knows of, and a given
        // handset carries a handful of them, so most names reaching here are for
        // apps that were never installed — and both state reads answer badly for
        // those. `isApplicationHidden` returns **true**, because "hidden" and
        // "not installed for this user" are the same bit underneath, so the
        // restore would report *Unhid com.ecosia.android* on a phone that has
        // never had it. `isPackageSuspended` throws, and the platform logs a
        // stack trace at error level on its way out — once per absent package,
        // per sweep, every fifteen minutes.
        //
        // Watched on the API 36 emulator, 2026-08-15: four such lines and four
        // stack traces in a single lock sweep. Nothing was broken by it, which is
        // the problem — a log that cries wolf about apps it never had is the log
        // somebody has to read when a phone really does misbehave.
        if (!isKnown(packageName)) return

        runCatching {
            if (isHidden(packageName)) {
                dpm.setApplicationHidden(admin, packageName, false)
                Log.i(TAG, "Unhid $packageName: the policy allows it again")
            }
        }.onFailure { Log.w(TAG, "Could not unhide $packageName", it) }

        runCatching {
            if (isSuspended(packageName)) {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                Log.i(TAG, "Unsuspended $packageName: the policy allows it again")
            }
        }.onFailure { Log.w(TAG, "Could not unsuspend $packageName", it) }
    }

    /**
     * Removes [packageName]: uninstall for user-installed apps, hide for
     * preinstalled ones **and for anything a switch can put back**.
     *
     * The second half arrived on 2026-08-19, from use. Switching the browser
     * chooser back and forth uninstalled and re-downloaded herald every time —
     * a quarter of a gigabyte per change — and switching *Allow WhatsApp* off
     * and on again lost the chats, because an uninstall is permanent and nothing
     * reinstalls a user-installed app. Both are controls a parent is expected to
     * change their mind about, so both now hide: the app stops existing as far
     * as the launcher is concerned, and its data is untouched underneath. See
     * [Removal.reversible] for which removals qualify and which deliberately do
     * not.
     *
     * `PackageInstaller.uninstall` fails on system apps — Chrome, Samsung
     * Internet and OEM browsers can never be uninstalled — so those are hidden
     * instead, which is what actually makes the DNS-only architecture safe: with
     * every other browser gone, no app is left that can run its own encrypted
     * DNS and route around the filter.
     *
     * **Every branch out of here ends at [hideOrSuspend], and that is the point
     * of the 2026-08-14 work.** The bug reported as "YouTube will not hide" was
     * one rung of one branch giving up quietly; the same shape sat in the other
     * branch, where an uninstall the platform refused was logged and forgotten
     * by [PackageRemovalReceiver]. A package this method returns anything but
     * [Action.FAILED] for is a package that cannot be opened, whichever route it
     * took, and a [Action.FAILED] is visible on the phone rather than only in a
     * log nobody reads — see [standings].
     */
    fun remove(packageName: String, reversible: Boolean = false): Action {
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "Not device owner; cannot remove $packageName")
            return Action.FAILED
        }

        // Two reasons to hide rather than uninstall: the platform refuses to
        // uninstall a system app, and a removal a switch can undo has to keep
        // the app's data. See [Removal.reversible].
        val hide = isSystemPackage(packageName) || reversible
        // Which branch a package took is the first question worth asking when one
        // of them survives a lock, and until 2026-08-14 the log did not say. The
        // owner found com.google.android.youtube still there after the option was
        // switched off while com.google.android.apps.youtube.music had gone, and
        // nothing on the device could distinguish "hide refused" from "uninstall
        // removed the update and left the factory build".
        Log.i(TAG, "Removing $packageName by ${if (hide) "hiding" else "uninstalling"} it")
        return if (hide) hideOrSuspend(packageName) else uninstall(packageName)
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
        // Uninstall fails on packages that turn out to be system apps after all.
        //
        // This catch only covers a session that could not be *started*. The
        // session's own verdict arrives later, at [PackageRemovalReceiver],
        // which drops through to the same ladder — that is the other half of
        // this fallback and it was missing until 2026-08-15.
        hideOrSuspend(packageName)
    }

    /**
     * Makes [packageName] unopenable without uninstalling it: hide, and failing
     * that suspend.
     *
     * **Two mechanisms rather than one, because the platform exempts packages
     * from each of them separately.** `setApplicationHidden` is refused for
     * `com.google.android.youtube` on the owner's Moto G15 — it returns false,
     * every time, while `com.google.android.apps.youtube.music` beside it
     * succeeds — and `setPackagesSuspended` works on exactly that package, which
     * was checked by hand on that handset before this was written. Nothing in
     * this app decided that and no policy edit changes it: the two packages are
     * named identically by the document and take an identical path to here.
     *
     * A suspended app stays in the launcher and says it is unavailable when
     * tapped, where a hidden one disappears. Worse cosmetically, and the same in
     * the way that matters. Hiding stays the first rung deliberately (decided
     * 2026-08-15): suspending everything would make one phone consistent with
     * itself at the cost of putting a row of dead icons on every managed phone,
     * which also advertises the blocklist to whoever is holding it.
     *
     * **The return value is not the answer; the state is.** Both calls report
     * refusal by their return rather than by throwing, and this class has now
     * been wrong twice about trusting what a platform call said it did. So each
     * rung is *checked* — `isApplicationHidden`, `isPackageSuspended` — and a
     * call that claims success over a package that did not move drops through to
     * the next rung anyway. The wrong guess is cheap in that direction: a hidden
     * app that gets suspended as well is still hidden, and [restore] undoes both.
     *
     * Public because [PackageRemovalReceiver] needs the same ladder when an
     * uninstall comes back refused. Whatever this does has to be undone by
     * [restoreNowAllowed] and [unhideAll], or an option switched back on leaves
     * a dead icon behind.
     */
    fun hideOrSuspend(packageName: String): Action {
        val accepted = runCatching { dpm.setApplicationHidden(admin, packageName, true) }
            .onFailure { Log.e(TAG, "Could not hide $packageName", it) }
            .getOrDefault(false)

        if (accepted && isHidden(packageName)) return Action.HIDDEN

        Log.w(
            TAG,
            if (accepted) {
                "$packageName reports itself visible after a hide the platform accepted; " +
                    "suspending it instead"
            } else {
                "The platform refused to hide $packageName; suspending it instead"
            },
        )
        return suspend(packageName)
    }

    /** The last rung. Reached only when hiding was refused or did not take. */
    private fun suspend(packageName: String): Action {
        val refused = runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), true) }
            .onFailure { Log.e(TAG, "Could not suspend $packageName", it) }
            .getOrNull()

        return if (refused != null && refused.isEmpty() && isSuspended(packageName)) {
            Log.i(TAG, "Suspended $packageName; it stays installed but cannot be opened")
            Action.SUSPENDED
        } else {
            // The end of the ladder, and the one outcome a parent has to be able
            // to see: policy says this app is not allowed and the phone is still
            // going to open it. It is reported on the configuration screen's
            // diagnostics rather than left here, because a log line on a locked
            // phone with no adb is not a channel to anyone.
            Log.w(TAG, "$packageName can be neither hidden nor suspended; it stays usable")
            Action.FAILED
        }
    }

    /**
     * The two state reads the ladder checks itself against.
     *
     * Both are ordinary public SDK, and both take the admin component — the
     * comment that used to sit in [restore] claiming there was no
     * `isPackageSuspended` for a Device Owner was simply wrong, and it cost this
     * class a blind unconditional write where a read would do.
     */
    private fun isHidden(packageName: String): Boolean =
        runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)

    private fun isSuspended(packageName: String): Boolean =
        runCatching { dpm.isPackageSuspended(admin, packageName) }.getOrDefault(false)

    /**
     * Whether the device carries this package at all, hidden or not.
     *
     * `MATCH_UNINSTALLED_PACKAGES` is the flag that separates the two cases that
     * matter here: a hidden package is found with it and absent without it,
     * while a package that was never installed is absent either way. Everything
     * that reads package state has to make that distinction first — see
     * [restore] for what happens when it does not.
     */
    private fun isKnown(packageName: String): Boolean = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
    }.isSuccess

    /**
     * Gives every package on the phone back: unhidden and unsuspended. Part of
     * removal.
     *
     * **`MATCH_UNINSTALLED_PACKAGES` is load-bearing.** A hidden package is
     * absent from an ordinary `getInstalledApplications`, so without that flag
     * this would enumerate exactly the apps that need nothing doing to them and
     * miss every one it is here for.
     *
     * The un-suspend is one batched call rather than one per package: this runs
     * while a parent waits on the removal screen, `setPackagesSuspended` takes an
     * array and reports refusals by returning them rather than by throwing, and
     * a few hundred package names is nowhere near a binder transaction limit. If
     * the batch throws all the same, every package is retried singly — this is
     * the path that has to leave nothing behind on a device drawbridge no longer
     * manages, and there is no third chance after it.
     */
    fun unhideAll() {
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        val installed = packageManager
            .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }

        installed.forEach { packageName ->
            runCatching {
                if (dpm.isApplicationHidden(admin, packageName)) {
                    dpm.setApplicationHidden(admin, packageName, false)
                }
            }.onFailure { Log.w(TAG, "Could not unhide $packageName on removal", it) }
        }

        runCatching { dpm.setPackagesSuspended(admin, installed.toTypedArray(), false) }
            .onFailure {
                Log.w(TAG, "Batched un-suspend failed; retrying one at a time", it)
                installed.forEach { packageName ->
                    runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), false) }
                        .onFailure { e ->
                            Log.w(TAG, "Could not unsuspend $packageName on removal", e)
                        }
                }
            }
    }

    /** True if [packageName] registers an activity that can open `https://` links. */
    fun isBrowser(packageName: String): Boolean = packageName in browsersOnDevice()

    /**
     * Every package on the phone that can open an `https://` link.
     *
     * One query answers this for the whole device, which is why [standings] asks
     * it once rather than asking [isBrowser] per installed package — that would
     * be one binder round trip per app on the phone to answer a question the
     * platform answers in full the first time.
     *
     * Hidden packages answer no intent queries and are absent here, which is the
     * same property that stops [restorable] being generalised.
     */
    private fun browsersOnDevice(): Set<String> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The browsers this phone may keep: what the policy sanctions, narrowed by
     * what the parent chose. See [BrowserSettings.allowedBrowsers].
     */
    private fun allowedBrowsers(policy: Policy): Set<String> =
        BrowserSettings.allowedBrowsers(policy, BrowserSettings(appContext).choice)

    private fun isProtected(
        packageName: String,
        policy: Policy,
        allowedBrowsers: Set<String>,
    ): Boolean =
        packageName == appContext.packageName ||
            packageName in allowedBrowsers ||
            // **The build's own browser, and only while every browser is
            // allowed.** This line is the fallback for a phone that has not read
            // a document yet, where `browserPackages` is whatever the model
            // defaults to. It used to be unconditional, which would now mean
            // herald surviving *no browser at all* — the one choice whose entire
            // content is that it does not.
            (packageName == BuildConfig.ALLOWED_BROWSER_PACKAGE && isEveryBrowserAllowed()) ||
            packageName in policy.exemptPackages ||
            // **The whitelist, and it sits here rather than in a branch of its
            // own.** Blocking `parental guidance` takes a phone's messengers
            // with it — every one of them carries that rating, because ungraded
            // conversation is what it means — so the rule is only affordable
            // because this list pays for it. Threema, Session, Zoom, Strava, the
            // recipe apps and the general-purpose assistants are all here.
            //
            // Consulted before the blocklist, which is what makes it dangerous
            // and why `policytool.py sign` refuses a document where the two
            // overlap: an entry on both lists is *unblocked*, silently. It also
            // refuses one that names a package an option governs, which would
            // leave the parent's switch moving and changing nothing.
            policy.appRatings?.isAlwaysAllowed(packageName) == true ||
            packageName in NEVER_TOUCH ||
            NEVER_TOUCH_PREFIXES.any { packageName.startsWith(it) }

    private fun isEveryBrowserAllowed(): Boolean =
        BrowserSettings(appContext).choice == BrowserSettings.Choice.ALL

    companion object {
        private const val TAG = "AppBlocker"

        /**
         * Whether a package that policy disallows may be removed *now*, given
         * what it is and what state the phone is in.
         *
         * Expressed against plain values rather than against the policy singleton
         * and a live `PackageManager`, so every branch is reachable from a unit
         * test — the same reasoning as [app.drawbridge.dpc.vpn.dns.DnsFilter.decide].
         * This is the rule that decides whether an unlocked phone can be talked
         * around, so it should not be checkable only by holding one.
         *
         * Callers apply it *after* deciding the package is disallowed at all:
         * this answers "now or at the lock", not "at all".
         *
         * **There is no pre-lock window in front of it any more.** Until
         * 2026-08-17 [evaluate] returned early on a phone that had never been
         * locked, so a freshly installed drawbridge removed nothing until somebody
         * pressed the button. It now runs from installation, and this function is
         * what keeps that honest: an app **no switch can bring back** goes at
         * once, and one a switch still governs — WhatsApp, Telegram, YouTube,
         * streaming, a browser the *chooser* narrowed away — waits for the lock,
         * because the parent has not answered that question yet.
         */
        internal fun actsNow(isDeferred: Boolean, isLocked: Boolean): Boolean =
            !isDeferred || isLocked

        /**
         * Whether this package's fate is still an open question a control on the
         * configuration screen could answer — in which case it waits for the
         * lock, and otherwise it goes now.
         *
         * **This asks about the package, not about the removal**, and the
         * distinction cost a build. It answers one question only: *is there a
         * switch on the configuration screen that still governs this app*. What
         * waits for the lock is decided per *reason* in [Removal], because a
         * package can be new and disallowed at once and the disallowed half is
         * the one that decides.
         *
         * Two things defer, and they are the same kind of thing:
         *
         *  - **What an option covers** — WhatsApp, Telegram, YouTube, the
         *    streaming catalogue. See [optionGoverned].
         *  - **A browser the policy sanctions that the browser choice has
         *    narrowed away** — Chrome under *only herald mono*, everything under
         *    *no browser*. The parent picked that from a chooser and can unpick
         *    it, and the website has promised since before it was built that the
         *    choice lands at the lock.
         * **A browser the policy never sanctioned is not deferred**, and that
         * distinction is the whole reason this is not simply "browsers wait
         * too". Opera is a way around a DNS-only filter — it ships an in-browser
         * proxy over 443 that no DNS rule sees and `DISALLOW_CONFIG_VPN` does
         * not touch — so it goes whether the phone is locked or not. Chrome under
         * *only herald mono* is a preference, and a reversible one.
         *
         * Everything else — the policy's own blocklist — goes now. Installing
         * drawbridge is the decision that social media is not on this phone, and
         * there is no second question to wait for.
         */
        internal fun deferred(
            packageName: String,
            policy: Policy,
            allowedBrowsers: Set<String>,
        ): Boolean =
            packageName in optionGoverned(policy) ||
                (packageName in policy.browserPackages && packageName !in allowedBrowsers)

        /**
         * Every package whose fate a switch on the configuration screen can
         * change — the union of what each option exempts or allows, whether that
         * option is on or off.
         *
         * **This is the line the 2026-08-15 change draws**, and the two sides of
         * it are different kinds of decision:
         *
         *  - **The policy's list** — social media, gambling, AI companions, the
         *    proxy and VPN apps — is what somebody installs drawbridge *for*.
         *    There is no second question to ask about it, so it goes as soon as
         *    it is seen, and an unlock does not hand it back. Before this, an
         *    unlocked phone quietly re-accumulated exactly what it was installed
         *    to remove.
         *  - **What an option covers** — WhatsApp, Telegram, YouTube, the
         *    streaming catalogue — is a question the parent answers with a
         *    switch, and they may not have answered it yet. Removing those while
         *    unlocked would take an app away from somebody halfway through
         *    deciding to allow it, and for a user-installed app an uninstall is
         *    permanent: [restoreNowAllowed] can unhide a preinstalled app when
         *    the option comes on, but nothing reinstalls one that was
         *    uninstalled.
         *
         * Read from the options rather than from the *enabled* ones on purpose.
         * An option that is **on** never reaches here at all — its packages are
         * in `exempt_packages`, so [isProtected] has already declined them — so
         * this set is only ever consulted for options that are off, which are
         * precisely the ones a parent might still switch on.
         *
         * A pure function of the policy, like [actsNow] and [restorable], because
         * this project has now paid four times for a rule that could only be
         * checked by holding a phone.
         */
        /**
         * Whether the browser rule may act, given a package and the version of
         * the document in hand.
         *
         * **The one branch in [reasonToRemove] that has to ask whether a policy
         * has been read**, because it is the branch that removes what is *not*
         * named. `Policy(version = 0)` is what `PolicyManager` holds before it
         * loads anything, and an empty document's `browserPackages` falls back to
         * herald alone — so a sweep racing the load would answer *"this phone
         * allows no browser but herald"* and uninstall Chrome, Firefox and
         * Vivaldi on the strength of a document nobody has opened.
         *
         * Every other branch fails safe on an empty document: an empty blocklist
         * names nobody, a null `allowed_packages` is not allowlist mode, absent
         * `app_ratings` is no store rule, and the install lock is device-local
         * and never consults the policy.
         *
         * **The race was survivable until 2026-08-17**, because nothing was
         * removed before the first lock and by then the document has been read
         * many times over. Removal starting at *installation* is what makes this
         * load-bearing: the first sweep now runs minutes after the app arrives,
         * in a process still reading its own assets off disk.
         */
        internal fun browserRuleApplies(isBrowser: Boolean, policyVersion: Int): Boolean =
            isBrowser && policyVersion != 0

        /**
         * The store rule's reach, as a function of the two facts about a package
         * that decide it.
         *
         * Pure and tested for the same reason [actsNow], [deferred] and
         * [InstallLockSettings.outsideTheSet] are: it decides what gets removed,
         * and the case it was written for — a preinstalled app with an icon — is
         * one nobody can reproduce without an OEM handset and its preloads.
         *
         * The prose is on the instance [withinStoreReach]; the short version is
         * that "not from the Play Store" turned out to be the wrong exemption,
         * and "nobody can open it anyway" is the right one.
         */
        internal fun withinStoreReach(isPreinstalled: Boolean, hasLauncherEntry: Boolean): Boolean =
            !isPreinstalled || hasLauncherEntry

        internal fun optionGoverned(policy: Policy): Set<String> =
            policy.options
                .flatMapTo(mutableSetOf()) { it.exemptPackages + it.allowedPackages }

        /**
         * The packages a restore may bring back: what the policy names as an
         * allowed browser or as exempt.
         *
         * A pure function of the policy so the rule can be checked without a
         * device, which is how the missing case would have been caught: a package
         * that is both blocked by name and exempted by an option **must** be
         * restorable, because exempt beats blocked everywhere else.
         */
        internal fun restorable(policy: Policy, allowedBrowsers: Set<String>): List<String> =
            (
                allowedBrowsers +
                    policy.exemptPackages +
                    // A package the whitelist newly names may have been hidden
                    // before it was named — which is the ordinary case, since
                    // the list grows in response to somebody losing an app. This
                    // has to agree with `isProtected` or the two disagree about
                    // the same package, which is the bug the blocked-list
                    // subtraction caused on 2026-08-13.
                    (policy.appRatings?.allowedPackages ?: emptyList())
                ).distinct()

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
