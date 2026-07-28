package app.drawbridge.dpc.ui

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.security.ParentCredentials
import app.drawbridge.dpc.vpn.DnsFilterService

/**
 * First-run setup: choose the parent PIN, then write down the one-time recovery
 * code before anything is locked down.
 *
 * The recovery code is shown once and only its hash is kept, so the flow refuses
 * to continue until the parent confirms they have it. Losing both the PIN and
 * the code leaves only a destructive wipe as a way out.
 */
class SetupActivity : AppCompatActivity() {

    private val credentials by lazy { ParentCredentials(this) }
    private val deviceOwner by lazy { DeviceOwnerManager(this) }

    private lateinit var pinStep: View
    private lateinit var recoveryStep: View
    private lateinit var pinField: EditText
    private lateinit var confirmField: EditText
    private lateinit var recoveryCodeView: TextView
    private lateinit var recoveryConfirmed: CheckBox
    private lateinit var finishButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<View>(R.id.root).applyScreenInsets()

        pinStep = findViewById(R.id.pinStep)
        recoveryStep = findViewById(R.id.recoveryStep)
        pinField = findViewById(R.id.pinField)
        confirmField = findViewById(R.id.pinConfirmField)
        recoveryCodeView = findViewById(R.id.recoveryCode)
        recoveryConfirmed = findViewById(R.id.recoveryConfirmed)
        finishButton = findViewById(R.id.finishButton)

        findViewById<TextView>(R.id.pinHint).text =
            getString(R.string.setup_pin_hint, ParentCredentials.MIN_PIN_LENGTH)

        findViewById<Button>(R.id.continueButton).setOnClickListener { onPinEntered() }

        recoveryConfirmed.setOnCheckedChangeListener { _, checked ->
            finishButton.isEnabled = checked
        }

        findViewById<Button>(R.id.copyRecoveryButton).setOnClickListener { shareRecoveryCode() }

        finishButton.setOnClickListener { finishSetup() }
    }

    private fun onPinEntered() {
        val pin = pinField.text.toString()
        val confirm = confirmField.text.toString()

        when {
            pin.length < ParentCredentials.MIN_PIN_LENGTH -> {
                toast(getString(R.string.setup_pin_too_short, ParentCredentials.MIN_PIN_LENGTH))
                return
            }
            pin != confirm -> {
                toast(getString(R.string.setup_pin_mismatch))
                return
            }
        }

        val recoveryCode = credentials.configure(pin)
        recoveryCodeView.text = recoveryCode

        pinStep.visibility = View.GONE
        recoveryStep.visibility = View.VISIBLE
    }

    /**
     * Offers the code to a printing or notes app. Not the clipboard: on this
     * device the clipboard is readable by whoever is holding the phone.
     */
    private fun shareRecoveryCode() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.setup_recovery_subject))
            putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.setup_recovery_body, recoveryCodeView.text.toString()),
            )
        }
        startActivity(Intent.createChooser(intent, getString(R.string.setup_recovery_share)))
    }

    private fun finishSetup() {
        deviceOwner.applyManagedDevicePolicy()
        requestBatteryOptimisationExemption()
        DnsFilterService.requestStart(this)

        if (!deviceOwner.isDeviceOwner) {
            // Setup still succeeded — the PIN is stored and the filter can run as
            // an ordinary user-approved VPN. Only the tamper-resistance is
            // missing, so say so rather than implying the device is locked down.
            toast(getString(R.string.setup_done_not_owner))
        } else {
            toast(getString(R.string.setup_done))
        }

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    /**
     * Asks to be exempted from battery optimisation, so the policy poller and
     * the filter service survive on aggressive OEM builds.
     *
     * Xiaomi, Huawei and Oppo/Realme also run proprietary "autostart" managers
     * that no API can reach; those need a manual step, documented in the setup
     * guide rather than attempted here.
     */
    private fun requestBatteryOptimisationExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return

        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData("package:$packageName".toUri()),
            )
        }.onFailure {
            // Some ROMs do not implement the dialog; not fatal, and the always-on
            // VPN keeps the process alive on a properly provisioned device.
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
