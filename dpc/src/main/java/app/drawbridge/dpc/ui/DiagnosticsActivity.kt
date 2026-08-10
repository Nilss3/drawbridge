package app.drawbridge.dpc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import app.drawbridge.dpc.vpn.DnsFilterService

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
            appendLine("blocked packages:  ${policy.blockedPackages.size}")
            appendLine("browsers allowed:  ${policy.browserPackages.joinToString()}")
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

    private fun isIgnoringBatteryOptimisations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun globalSetting(name: String): String =
        runCatching { Settings.Global.getInt(contentResolver, name).toString() }
            .getOrDefault("?")
}
