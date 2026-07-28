package app.drawbridge.dpc.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.security.ParentCredentials
import app.drawbridge.dpc.vpn.DnsFilterService
import app.drawbridge.policy.PolicyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parent-facing status screen: what is protecting the device right now, and
 * the two actions that matter — refresh the policy, and remove the controls.
 */
class MainActivity : AppCompatActivity() {

    private val deviceOwner by lazy { DeviceOwnerManager(this) }
    private val credentials by lazy { ParentCredentials(this) }

    private lateinit var ownershipStatus: TextView
    private lateinit var filterStatus: TextView
    private lateinit var policyStatus: TextView
    private lateinit var restrictionsStatus: TextView
    private lateinit var profileStatus: TextView
    private lateinit var setupButton: Button
    private lateinit var removeButton: Button
    private lateinit var profileButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.root).applyScreenInsets()

        ownershipStatus = findViewById(R.id.ownershipStatus)
        filterStatus = findViewById(R.id.filterStatus)
        policyStatus = findViewById(R.id.policyStatus)
        restrictionsStatus = findViewById(R.id.restrictionsStatus)
        profileStatus = findViewById(R.id.profileStatus)
        setupButton = findViewById(R.id.setupButton)
        removeButton = findViewById(R.id.removeButton)
        profileButton = findViewById(R.id.profileButton)

        setupButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        removeButton.setOnClickListener {
            startActivity(Intent(this, RemoveActivity::class.java))
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshPolicy() }

        profileButton.setOnClickListener {
            ProfilePicker(this, this) { render() }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val isOwner = deviceOwner.isDeviceOwner

        ownershipStatus.text = if (isOwner) {
            getString(R.string.status_device_owner_yes)
        } else {
            getString(R.string.status_device_owner_no)
        }

        filterStatus.text = if (DnsFilterService.isRunning) {
            getString(R.string.status_filter_running)
        } else {
            getString(R.string.status_filter_stopped)
        }

        val policy = DrawbridgeApplication.policy(this)
        lifecycleScope.launch {
            // The policy is loaded from disk asynchronously, so anything read
            // straight out of onResume would show the state before it arrived —
            // which is how the profile line came to say "no variants" on a
            // policy that had two.
            withContext(Dispatchers.IO) { policy.ensureLoaded() }

            val state = withContext(Dispatchers.IO) { policy.state() }
            val lastCheck = if (state.lastSuccessMillis > 0) {
                DateUtils.getRelativeTimeSpanString(state.lastSuccessMillis)
            } else {
                getString(R.string.status_policy_never)
            }
            policyStatus.text = getString(
                R.string.status_policy,
                policy.policy.value.version,
                lastCheck,
            )

            val profile = withContext(Dispatchers.IO) { policy.selectedProfile }
            profileStatus.text = if (profile == null) {
                getString(R.string.status_profile_none)
            } else {
                getString(R.string.status_profile, profile.name)
            }

            // Switching profile decides which apps may exist, so it is gated on
            // the same PIN as removal and hidden until that PIN exists.
            profileButton.visibility = if (policy.profiles.isNotEmpty() && credentials.isConfigured) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val restrictions = deviceOwner.activeRestrictions()
        restrictionsStatus.text = if (restrictions.isEmpty()) {
            getString(R.string.status_restrictions_none)
        } else {
            restrictions.joinToString("\n") { "• ${it.removePrefix("no_")}" }
        }

        setupButton.visibility = if (credentials.isConfigured) View.GONE else View.VISIBLE
        removeButton.visibility = if (isOwner && credentials.isConfigured) View.VISIBLE else View.GONE
    }

    private fun refreshPolicy() {
        lifecycleScope.launch {
            val message = when (val outcome = DrawbridgeApplication.policy(this@MainActivity).refresh()) {
                is PolicyManager.RefreshOutcome.Success ->
                    getString(R.string.policy_refreshed, outcome.version)
                is PolicyManager.RefreshOutcome.Failure ->
                    getString(R.string.policy_refresh_failed)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            render()
        }
    }
}
