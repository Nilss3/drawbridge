package app.drawbridge.dpc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.apps.InstallLockSettings
import app.drawbridge.dpc.apps.store.StoreCatalogue
import app.drawbridge.dpc.curfew.DisconnectSettings
import app.drawbridge.dpc.vpn.DnsFilterService
import java.time.LocalDateTime

/**
 * What this phone actually did, in a form someone can paste into a bug report.
 *
 * **English only, deliberately.** Every other screen is translated because a
 * parent reads it; this one is read by whoever is trying to work out why a
 * particular handset misbehaved, and it is quoted into an issue written in
 * English. Translating it would mean bug reports arriving in three languages
 * with no gain to the person who has to act on them.
 *
 * It ships in release builds rather than hiding behind a debug flag, because the
 * failures it exists for are exactly the ones that cannot be reproduced here.
 * drawbridge is provisioned onto hardware nobody involved owns — OEM setup
 * wizards differ, Play Protect differs, and a device whose provisioning went
 * wrong has no notification shade, a Settings that closes itself on launch, and
 * therefore no way to enable USB debugging. On such a phone this screen is the
 * only channel out. Making it a debug-only feature would remove it from every
 * device where it matters.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        findViewById<View>(R.id.root).applyScreenInsets()

        val report = buildReport()
        findViewById<TextView>(R.id.diagnosticsText).text = report

        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("drawbridge diagnostics", report))
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildReport(): String {
        val deviceOwner = DeviceOwnerManager(this)
        val policy = DrawbridgeApplication.policy(this).policy.value

        return buildString {
            appendLine("drawbridge diagnostics")
            appendLine("======================")
            appendLine()
            appendLine("drawbridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("adb retained:      ${BuildConfig.RETAIN_ADB_ACCESS}")
            // Reported because a build with a second unlock key must not be able
            // to pass for one without. Says only that one exists.
            appendLine("emergency key:     ${BuildConfig.EMERGENCY_KEY_SHA256.isNotEmpty()}")
            appendLine()
            appendLine("device:            ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android:           ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("build:             ${Build.DISPLAY}")
            appendLine("security patch:    ${Build.VERSION.SECURITY_PATCH}")
            appendLine()
            // The two that decide whether the phone believes it finished setup.
            // A device owner sitting on setup=0 is mid-wizard, however healthy
            // everything else looks, and that is the state with no notification
            // shade and a Settings that will not open.
            appendLine("user_setup_complete: ${ProvisioningLog.setupComplete(this@DiagnosticsActivity)}")
            appendLine("device_provisioned:  ${globalSetting("device_provisioned")}")
            appendLine()
            appendLine("device owner:      ${deviceOwner.isDeviceOwner}")
            // What the keyguard tells whoever picks the phone up. Reported
            // because it is the one thing standing in for factory reset
            // protection, and "it should be there" is not the same as seeing it.
            appendLine("lock screen says:  ${deviceOwner.lockScreenInfo() ?: "(nothing set)"}")
            appendLine("filter running:    ${DnsFilterService.isRunning}")
            // Denying the battery-optimisation prompt at lock time does not stop
            // the filter -- that is an always-on foreground VpnService and the
            // platform restarts it. What it stops is the *polling*: Doze can
            // defer the daily policy and update workers, on some OEMs for days,
            // so the phone goes on filtering against a blocklist that is quietly
            // out of date. Nothing else on the device reports that, which is
            // exactly why it belongs here.
            appendLine("battery exempt:    ${isIgnoringBatteryOptimisations()}")
            appendLine("policy version:    ${policy.version}")
            // Why the version is what it is, which the version alone never says.
            // On 2026-08-12 a phone sat on policy 36 after 37 was published and
            // there was no way to tell from the device whether the refresh had
            // failed, had not run, or had succeeded against a stale CDN copy --
            // the manual check's toast is transient and binary, and all three
            // look identical afterwards. The state has been written on every
            // refresh since the beginning; it was simply never shown.
            val state = DrawbridgeApplication.policy(this@DiagnosticsActivity).state()
            appendLine("policy checked:    ${timestamp(state.lastCheckMillis)}")
            appendLine("policy succeeded:  ${timestamp(state.lastSuccessMillis)}")
            appendLine("policy error:      ${state.lastError ?: "(none)"}")
            appendLine("policy url:        ${DrawbridgeApplication.policyConfig.policyUrl}")

            // Connectivity, for the same reason the policy lines above exist: on
            // 2026-08-12 a phone went offline at its curfew boundary and did not
            // come back, and nothing on the device could say whether the alarm
            // had fired, what the schedule was, or what state the controller
            // believed it was in. "next boundary" is the line that answers it —
            // a missing one is an alarm that was never set.
            val disconnect = DisconnectSettings(this@DiagnosticsActivity)
            val now = LocalDateTime.now()
            appendLine("disconnect mode:   ${disconnect.mode}")
            appendLine(
                "curfew weekdays:   ${disconnect.weekdayWindow.start}-${disconnect.weekdayWindow.end}",
            )
            appendLine(
                "curfew weekend:    ${disconnect.weekendWindow.start}-${disconnect.weekendWindow.end}",
            )
            appendLine("should be offline: ${disconnect.isOfflineAt(now)}")
            appendLine("next boundary:     ${disconnect.nextChangeAfter(now) ?: "(none)"}")
            appendLine("blocked packages:  ${policy.blockedPackages.size}")
            appendLine("browsers allowed:  ${policy.browserPackages.joinToString()}")

            // The install lock, and specifically its snapshot, because a wrong
            // one is invisible from every other angle. A phone that removes an
            // app the parent installed during an unlock and a phone that lets a
            // new one stay look identical from the outside; the size of the set
            // and when it was taken are what tell them apart. "(never taken)"
            // with the lock on is the failure — the rule is inert, and no line
            // above says so.
            val installLock = InstallLockSettings(this@DiagnosticsActivity)
            // The store catalogue, and specifically the failures, because
            // fail-open is only a deliberate choice while somebody can see how
            // much of it is happening. A phone quietly unable to reach
            // play.google.com keeps every app on it and looks identical to one
            // where the rule is working — `store unverified` is the only line
            // that tells the two apart.
            val store = StoreCatalogue(this@DiagnosticsActivity).stats()
            appendLine("store rule:        ${policy.appRatings?.let { "on, ${it.storeRegion}" } ?: "(policy has none)"}")
            appendLine("store cached:      ${store.known} (${store.usable} usable)")
            appendLine("store unverified:  ${store.failed}")
            appendLine("store last fetch:  ${timestamp(store.newestFetchMillis)}")

            appendLine("install lock:      ${installLock.isEnabled}")
            appendLine(
                "installed set:     " +
                    (installLock.snapshot?.let { "${it.size} packages" } ?: "(never taken)"),
            )
            appendLine("set taken:         ${timestamp(installLock.snapshotTakenAt)}")

            // What became of the blocklist on *this* phone, which no other line
            // here or anywhere else on the device could say. On 2026-08-14 the
            // owner reported the YouTube app still installed after its option was
            // switched off and the phone locked, and answering "did the rule
            // decline it, did the platform refuse it, or did something put it
            // back" took a new build, a cable and an evening. `still usable`
            // names it in one line.
            //
            // One caveat belongs with it: this screen only opens while the phone
            // is unlocked, and an app installed during an unlock is legitimately
            // still usable until the next lock. A *blocked* app listed here after
            // a lock is the failure.
            val standings = AppBlocker(this@DiagnosticsActivity).standings()
            val counts = AppBlocker.Standing.entries.associateWith { standing ->
                standings.values.count { it == standing }
            }
            appendLine(
                "blocklist state:   " +
                    counts.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" },
            )
            val usable = standings.filterValues { it == AppBlocker.Standing.PRESENT }.keys
            appendLine("still usable:      ${if (usable.isEmpty()) "(none)" else usable.size}")
            usable.forEach { appendLine("  $it") }
            appendLine()
            appendLine("restrictions in force:")
            val restrictions = deviceOwner.activeRestrictions()
            if (restrictions.isEmpty()) {
                appendLine("  (none)")
            } else {
                restrictions.forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("provisioning record:")
            appendLine(ProvisioningLog.read(this@DiagnosticsActivity) ?: "  (nothing recorded)")
        }
    }

    /** Absolute rather than "3 hours ago": this is pasted into bug reports. */
    private fun timestamp(millis: Long): String =
        if (millis == 0L) {
            "(never)"
        } else {
            DateUtils.formatDateTime(
                this,
                millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
            )
        }

    private fun isIgnoringBatteryOptimisations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun globalSetting(name: String): String =
        runCatching { Settings.Global.getInt(contentResolver, name).toString() }
            .getOrDefault("?")
}
