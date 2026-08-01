package app.drawbridge.dpc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.drawbridge.dpc.R
import app.drawbridge.dpc.security.ParentKey

/**
 * The lock, in its two moments.
 *
 * **Reveal** happens once, when the parent locks the device: the key is minted
 * here and shown here, and this is the only time it exists in a form anyone can
 * read. Leaving without keeping it is allowed — it is a legitimate way to make
 * the configuration permanent on purpose — but it is not allowed to happen by
 * accident, which is what the checkbox and the second dialog are for.
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

        // Minted on the way in rather than on the way out, so the screen can
        // never show a key that is not the one now stored, and so backing out of
        // the reveal cannot leave the device unlocked after the parent has been
        // told it is locked.
        revealedKey = savedInstanceState?.getString(STATE_KEY)
            ?: if (intent.getBooleanExtra(EXTRA_MINT, false)) parentKey.lock() else null

        val revealing = revealedKey != null
        revealStep.visibility = if (revealing) View.VISIBLE else View.GONE
        challengeStep.visibility = if (revealing) View.GONE else View.VISIBLE
        keyView.text = revealedKey.orEmpty()
        if (!revealing) showLockHistory()

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
        done.setOnClickListener { finish() }

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

    private fun confirmLeavingWithoutKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_reveal_discard_title)
            .setMessage(R.string.lock_reveal_discard_message)
            .setPositiveButton(R.string.lock_reveal_discard_yes) { _, _ -> finish() }
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
