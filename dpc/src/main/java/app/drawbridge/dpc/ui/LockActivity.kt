package app.drawbridge.dpc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.dpc.update.AppInstaller
import app.drawbridge.policy.PolicyManager
import kotlinx.coroutines.launch

/**
 * The lock, in its two moments.
 *
 * **Reveal** happens once, when the parent locks the device: the key is
 * generated here and shown here, and this is the only time it exists in a form
 * anyone can read. Deciding to keep no copy of it is allowed — it is a
 * legitimate way to make the configuration permanent on purpose — but it is not
 * allowed to happen by accident, which is what the checkbox and the second
 * dialog are for.
 *
 * Nothing is stored until one of those two says so. The device is sealed by
 * [sealWithKey] and by nothing else, so a reveal that is walked away from leaves
 * the phone as it was.
 *
 * **Challenge** is every time after that: the key, typed back, opens the
 * configuration screen. There is no attempt limit. A six-digit PIN needed one; a
 * hundred-bit key does not, and a lockout on the only way in is a way to strand
 * the parent for half an hour with nothing else to try.
 */
class LockActivity : AppCompatActivity() {

    private val parentKey by lazy { ParentKey(this) }

    /**
     * Non-null only while revealing.
     *
     * Kept as state rather than read back off the TextView because a TextView
     * does not save its own text: rotating the phone during the reveal would
     * have dropped the only copy of a key that had already been committed to,
     * leaving the device locked and unopenable.
     */
    private var revealedKey: String? = null

    private lateinit var revealStep: View
    private lateinit var challengeStep: View
    private lateinit var keyView: TextView
    private lateinit var keyField: EditText
    private lateinit var challengeError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        findViewById<View>(R.id.root).applyScreenInsets()

        revealStep = findViewById(R.id.revealStep)
        challengeStep = findViewById(R.id.challengeStep)
        keyView = findViewById(R.id.keyView)
        keyField = findViewById(R.id.keyField)
        challengeError = findViewById(R.id.challengeError)


        // Generated here, stored nowhere yet. This used to call parentKey.lock(),
        // which sealed the device the instant this screen appeared — so pressing
        // home before writing the key down left a phone locked with a key that
        // had existed only on screen, recoverable only by factory reset.
        // Committing is now the last step rather than the first; see
        // [ParentKey.commit].
        revealedKey = savedInstanceState?.getString(STATE_KEY)
            ?: if (intent.getBooleanExtra(EXTRA_MINT, false)) ParentKey.generateKey() else null

        val revealing = revealedKey != null
        revealStep.visibility = if (revealing) View.VISIBLE else View.GONE
        challengeStep.visibility = if (revealing) View.GONE else View.VISIBLE
        keyView.text = revealedKey.orEmpty()
        if (!revealing) {
            showLockHistory()
            showUpdateNotice()
        }

        if (revealing) {
            // Keeps the key out of screenshots and out of the recents thumbnail,
            // for the same reason it is not offered to the clipboard: on this
            // phone, whoever is holding it can read the gallery. Print, write it
            // down, or share it off the device.
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        val confirmed = findViewById<CheckBox>(R.id.keyConfirmed)
        val done = findViewById<Button>(R.id.revealDoneButton)
        done.isEnabled = confirmed.isChecked
        confirmed.setOnCheckedChangeListener { _, checked -> done.isEnabled = checked }
        done.setOnClickListener { sealWithKey() }

        findViewById<Button>(R.id.shareKeyButton).setOnClickListener { shareKey() }
        findViewById<Button>(R.id.unlockButton).setOnClickListener { attemptUnlock() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Backing out of the challenge closes drawbridge rather than
                    // returning to whatever was under it — which, when this
                    // screen was reached from the configuration, would be the
                    // configuration.
                    if (revealing) confirmLeavingWithoutKey() else finishAffinity()
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KEY, revealedKey)
    }

    /**
     * Offers the update from behind the lock, which is the whole point of having
     * it here.
     *
     * drawbridge can no longer install its own updates — Play Protect refuses
     * them, see [UpdateActivity] — so someone has to press a button. That
     * someone is a parent holding a phone that is, in the normal case, locked.
     * If the only route were the configuration screen they would have to unlock
     * first, which discards their key and mints a new one to write down: a
     * credential rotation as the price of a maintenance task, which is a good
     * way to make sure the maintenance never happens.
     *
     * Nothing is given away by offering it here. The APK is named by the signed
     * policy and pinned by checksum, so the button installs the build the parent
     * already consented to or nothing at all.
     */
    private fun showUpdateNotice() {
        val notice = findViewById<View>(R.id.updateNotice)
        notice.visibility =
            if (AppInstaller(this).availableSelfUpdate() != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.updateButton).setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
    }

    /**
     * The two dates, on the screen a caregiver reaches by picking the phone up.
     *
     * This is the cheap tamper check the lock cannot otherwise give: neither
     * date survives a factory reset, so a phone that was wiped and set up again
     * says so here, however innocent it looks. Neither is authenticated — a
     * determined child with the key can re-lock and move the second one — but
     * the first cannot be moved at all without clearing drawbridge's data, which
     * is the case worth catching.
     */
    private fun showLockHistory() {
        val protectedSince = findViewById<TextView>(R.id.protectedSince)
        val lockedSince = findViewById<TextView>(R.id.lockedSince)
        val hint = findViewById<TextView>(R.id.sinceHint)

        val protectedAt = parentKey.protectedSince
        val lockedAt = parentKey.lockedSince
        if (protectedAt <= 0) {
            protectedSince.visibility = View.GONE
            lockedSince.visibility = View.GONE
            hint.visibility = View.GONE
            return
        }

        protectedSince.text = getString(R.string.lock_since_protected, formatMoment(protectedAt))
        // Only worth a second line when it says something the first does not.
        val sameMoment = lockedAt <= 0 ||
            DateUtils.formatDateTime(this, lockedAt, DATE_AND_TIME) ==
            DateUtils.formatDateTime(this, protectedAt, DATE_AND_TIME)
        lockedSince.visibility = if (sameMoment) View.GONE else View.VISIBLE
        if (!sameMoment) {
            lockedSince.text = getString(R.string.lock_since_locked, formatMoment(lockedAt))
        }
    }

    private fun formatMoment(millis: Long): String =
        DateUtils.formatDateTime(this, millis, DATE_AND_TIME)

    /**
     * Offers the key to a printing or notes app. Not the clipboard: on this
     * device the clipboard is readable by whoever is holding the phone.
     */
    private fun shareKey() {
        val key = revealedKey ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.lock_reveal_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.lock_reveal_body, key))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.lock_reveal_share)))
    }

    /**
     * The only two ways the device actually gets sealed: *Done* with the
     * checkbox ticked, and the deliberate "close without the key" dialog.
     *
     * Everything else — home, recents, the process being killed — leaves the
     * phone unsealed, and the key that was on screen is simply forgotten. That
     * is the whole point of the change: locking is something the parent does,
     * not something that happens to them while they are looking for a pen.
     *
     * The restrictions and the filter are already on by this point, applied by
     * [MainActivity.lockDevice] before this screen opened, and they stay on. An
     * abandoned reveal therefore leaves a filtered phone whose settings are
     * still reachable — recoverable, and the parent can lock again for a new
     * key. Unwinding enforcement here would be the worse trade: it would mean a
     * phone that briefly un-filters itself because somebody took a call.
     */
    private fun sealWithKey() {
        revealedKey?.let { parentKey.commit(it) }
        finish()
    }

    private fun confirmLeavingWithoutKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_reveal_discard_title)
            .setMessage(R.string.lock_reveal_discard_message)
            .setPositiveButton(R.string.lock_reveal_discard_yes) { _, _ -> sealWithKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun attemptUnlock() {
        val candidate = keyField.text.toString().trim()
        if (candidate.isEmpty()) return

        if (!parentKey.unlock(candidate)) {
            challengeError.setText(R.string.lock_challenge_incorrect)
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    /**
     * Diagnostics only. Removal is not offered here — it lives behind the key,
     * on the screen this one guards.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_lock, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionDiagnostics -> {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        R.id.actionRefresh -> {
            refreshPolicy()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * The manual policy check, from the screen the phone actually sits on.
     *
     * Deliberately not behind the key. A policy refresh cannot change what the
     * policy says — the document is signed, and this only fetches whatever is
     * already published — so the worst a child can do with it is give the phone
     * the newest rules slightly sooner.
     */
    private fun refreshPolicy() {
        lifecycleScope.launch {
            val policy = DrawbridgeApplication.policy(this@LockActivity)
            val message = when (val outcome = policy.refresh()) {
                is PolicyManager.RefreshOutcome.Success ->
                    getString(R.string.policy_refreshed, outcome.version)
                is PolicyManager.RefreshOutcome.Failure ->
                    getString(R.string.policy_refresh_failed)
            }
            Toast.makeText(this@LockActivity, message, Toast.LENGTH_LONG).show()
            showUpdateNotice()
        }
    }

    companion object {
        private const val EXTRA_MINT = "mint_key"
        private const val STATE_KEY = "revealed_key"

        private const val DATE_AND_TIME = DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_YEAR or
            DateUtils.FORMAT_SHOW_TIME

        /** Locks the device and opens the screen that shows the key, once. */
        fun mintKey(context: Context): Intent =
            Intent(context, LockActivity::class.java).putExtra(EXTRA_MINT, true)
    }
}
