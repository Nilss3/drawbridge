package app.drawbridge.dpc.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.security.ParentCredentials
import app.drawbridge.dpc.vpn.DnsFilterService
import java.util.concurrent.TimeUnit

/**
 * The sanctioned way out, gated behind the parent PIN or the printed recovery
 * code.
 *
 * This lifts every restriction and gives up Device Owner without wiping the
 * device — the child grows up, or the phone gets sold, and nothing is lost.
 */
class RemoveActivity : AppCompatActivity() {

    private val credentials by lazy { ParentCredentials(this) }
    private val deviceOwner by lazy { DeviceOwnerManager(this) }

    private lateinit var secretField: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remove)

        findViewById<View>(R.id.root).applyScreenInsets()

        secretField = findViewById(R.id.secretField)
        statusView = findViewById(R.id.statusView)

        findViewById<Button>(R.id.removeButton).setOnClickListener { attemptRemoval() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
    }

    private fun attemptRemoval() {
        val secret = secretField.text.toString().trim()
        if (secret.isEmpty()) return

        // Clear the previous message first: leaving "that is not the PIN" on
        // screen while the confirmation dialog opens reads as a rejection.
        statusView.text = ""

        when (val result = credentials.verify(secret)) {
            is ParentCredentials.VerifyResult.Correct -> confirmRemoval()

            is ParentCredentials.VerifyResult.LockedOut -> {
                statusView.text = getString(
                    R.string.remove_locked_out,
                    TimeUnit.MILLISECONDS.toSeconds(result.remainingMillis),
                )
            }

            is ParentCredentials.VerifyResult.Incorrect -> {
                // A wrong PIN might have been the recovery code instead, so try
                // that before reporting a failure.
                if (credentials.consumeRecoveryCode(secret)) {
                    confirmRemoval()
                } else {
                    statusView.text = if (result.retryDelayMillis > 0) {
                        getString(
                            R.string.remove_incorrect_locked,
                            TimeUnit.MILLISECONDS.toSeconds(result.retryDelayMillis),
                        )
                    } else {
                        getString(R.string.remove_incorrect)
                    }
                }
            }
        }
    }

    private fun confirmRemoval() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_confirm_title)
            .setMessage(R.string.remove_confirm_message)
            .setPositiveButton(R.string.remove_confirm_yes) { _, _ -> performRemoval() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performRemoval() {
        DnsFilterService.requestStop(this)

        val wasOwner = deviceOwner.isDeviceOwner
        // Restore hidden system browsers *before* giving up ownership: afterwards
        // there is no privilege left to un-hide them, and the device would be
        // stuck with Chrome permanently invisible.
        AppBlocker(this).unhideAll()
        val released = deviceOwner.releaseDeviceOwnership()

        DrawbridgeApplication.policy(this).clear()
        credentials.clear()

        val message = when {
            !wasOwner -> getString(R.string.remove_done_not_owner)
            released -> getString(R.string.remove_done)
            else -> getString(R.string.remove_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
