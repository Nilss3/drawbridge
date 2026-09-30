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
import app.drawbridge.policy.model.AppUpdate
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
    private lateinit var notesHeading: TextView
    private lateinit var notes: TextView

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
        notesHeading = findViewById(R.id.updateNotesHeading)
        notes = findViewById(R.id.updateNotes)

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
            // **The dotted version when the document names one.** A pair of
            // build numbers answers nothing a parent asked: *build 51* does not
            // say whether this is a fortnight or a year of work, and the number
            // written everywhere else about this project is the dotted one. A
            // document that predates `version_name` still has only the integer,
            // so both sentences stay.
            versions.text = available.versionName?.takeIf { it.isNotBlank() }?.let { named ->
                getString(R.string.update_available_named, BuildConfig.VERSION_NAME, named)
            } ?: getString(
                R.string.update_available_detail,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                available.versionCode,
            )
            installButton.isEnabled = true
        }
        showNotes(available)
        showOutcome()
    }

    /**
     * What the release says about itself, when it says anything.
     *
     * **An update somebody has to press a button for is one they are owed a
     * reason for.** Play Protect means drawbridge cannot install its own
     * updates, so every version that reaches a phone does so because a parent
     * read this screen and chose to act — and until now the only thing it told
     * them was that a larger number existed. That is a poor bargain to offer
     * somebody, and it is the reason this is drawn above the two buttons rather
     * than below the Play Protect walk: it is an input to the decision, not a
     * footnote to it.
     *
     * Heading and body go together, so a document with no notes shows neither
     * rather than a heading over nothing.
     */
    private fun showNotes(available: AppUpdate?) {
        val text = available?.displayNotes(Languages.current())
        val visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        notes.text = text.orEmpty()
        notes.visibility = visibility
        notesHeading.visibility = visibility
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
