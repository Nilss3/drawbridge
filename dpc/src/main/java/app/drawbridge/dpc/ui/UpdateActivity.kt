package app.drawbridge.dpc.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.R
import app.drawbridge.dpc.update.AppInstaller
import app.drawbridge.dpc.update.InstallOutcome
import kotlinx.coroutines.launch

/**
 * Updating drawbridge, by hand, with the reason it cannot happen by itself.
 *
 * drawbridge installed its own updates silently until 2026-08-10. Play Protect
 * refuses that install on any phone with a Google account, and five rounds of
 * experiment — every install-related permission, the install session, and
 * finally the package name — established that nothing in the APK changes its
 * mind. Only a differently-named build got through, which is not a fix.
 *
 * So the update became something the parent starts, on a screen that explains
 * the one step they have to take first. That is worse than a silent update and
 * better than a phone that never receives a fix again; see docs/handoff.md.
 *
 * **Reachable while the phone is locked**, deliberately. A locked phone is the
 * normal state, and unlocking to reach this screen would cost the parent their
 * key — `unlock()` discards it and the next lock mints a different one to write
 * down again. Making maintenance rotate the credential would be a good way to
 * ensure it never happens. Nothing here is a policy change: the APK is named by
 * the signed policy and pinned by checksum, so there is no privilege for anyone
 * to gain by pressing the button.
 */
class UpdateActivity : AppCompatActivity() {

    private val installer by lazy { AppInstaller(this) }
    private val outcome by lazy { InstallOutcome(this) }

    private lateinit var versions: TextView
    private lateinit var status: TextView
    private lateinit var installButton: Button

    /**
     * The install answers through a broadcast rather than a return value, so the
     * screen listens for the record of it rather than waiting on the call.
     */
    private val outcomeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> showOutcome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        findViewById<View>(R.id.root).applyScreenInsets()

        versions = findViewById(R.id.updateVersions)
        status = findViewById(R.id.updateStatus)
        installButton = findViewById(R.id.updateInstallButton)

        findViewById<Button>(R.id.playProtectButton).setOnClickListener { openPlayProtect() }
        installButton.setOnClickListener { install() }
    }

    override fun onResume() {
        super.onResume()
        outcome.observe(outcomeListener)
        render()
    }

    override fun onPause() {
        outcome.stopObserving(outcomeListener)
        super.onPause()
    }

    private fun render() {
        val available = installer.availableSelfUpdate()
        if (available == null) {
            versions.text = getString(R.string.update_none, BuildConfig.VERSION_NAME)
            installButton.isEnabled = false
        } else {
            versions.text = getString(
                R.string.update_available_detail,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                available.versionCode,
            )
            installButton.isEnabled = true
        }
        showOutcome()
    }

    private fun showOutcome() {
        val last = outcome.latest()?.takeIf { it.packageName == packageName }
        status.text = when {
            last == null -> ""
            last.succeeded -> getString(R.string.update_result_installed, last.versionCode)
            last.looksBlocked -> getString(R.string.update_result_blocked)
            else -> getString(
                R.string.update_result_failed,
                last.message ?: last.status.toString(),
            )
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun install() {
        // Cleared first, so what the screen reports afterwards cannot be the
        // answer to a previous attempt.
        outcome.clear()
        installButton.isEnabled = false
        status.visibility = View.VISIBLE
        status.setText(R.string.update_result_started)

        lifecycleScope.launch {
            when (val result = installer.installSelfUpdate()) {
                is AppInstaller.Result.Started -> Unit // The receiver has the rest.
                is AppInstaller.Result.UpToDate -> render()
                is AppInstaller.Result.Failed -> {
                    // A failure this early is the download or the checksum, not
                    // the phone refusing the package.
                    status.text = getString(R.string.update_result_failed, result.reason)
                    installButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Best effort, in descending order of usefulness. Play services has had a
     * settings screen for this under several names across versions, so a
     * hard-coded action would break silently on the builds that lack it —
     * falling back to the Play Store lands the parent where the written steps
     * start, and Settings is better than nothing.
     */
    private fun openPlayProtect() {
        val candidates = listOfNotNull(
            Intent(PLAY_PROTECT_SETTINGS),
            packageManager.getLaunchIntentForPackage(PLAY_STORE),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )

        val intent = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent == null) {
            status.visibility = View.VISIBLE
            status.setText(R.string.update_no_play_protect)
            return
        }
        startActivity(intent)
    }

    private companion object {
        const val PLAY_PROTECT_SETTINGS = "com.google.android.gms.settings.VERIFY_APPS_SETTINGS"
        const val PLAY_STORE = "com.android.vending"
    }
}
