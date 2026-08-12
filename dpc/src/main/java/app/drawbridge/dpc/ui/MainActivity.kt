package app.drawbridge.dpc.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.curfew.CurfewController
import app.drawbridge.dpc.curfew.DisconnectSettings
import app.drawbridge.dpc.policy.SelectionProvider
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.dpc.update.AppInstaller
import app.drawbridge.dpc.vpn.DnsFilterService
import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.model.PolicyOption
import app.drawbridge.policy.model.Profile
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * drawbridge's configuration screen, and the only one a parent normally sees:
 * what this phone is allowed to do, and the button that seals it.
 *
 * There is no separate setup wizard. A wizard is for a sequence that has to be
 * walked once, and this is a page that has to be *re-read* — a year later, when
 * the child is a year older and the answer to one of the switches has changed.
 *
 * There is also no "turn on protection" button any more. Protecting the phone
 * and locking it were always the same intention, and splitting them into two
 * buttons meant a phone could sit configured, unlocked and unfiltered while
 * looking finished. [confirmLock] does both.
 *
 * When the device is locked this screen is not reachable at all: [onCreate]
 * hands over to [LockActivity] before anything is drawn.
 */
class MainActivity : AppCompatActivity() {

    private val deviceOwner by lazy { DeviceOwnerManager(this) }
    private val parentKey by lazy { ParentKey(this) }
    private val policy by lazy { DrawbridgeApplication.policy(this) }

    private val disconnect by lazy { DisconnectSettings(this) }

    private lateinit var updateNotice: View
    private lateinit var disconnectContainer: LinearLayout
    private lateinit var curfewSchedule: LinearLayout
    private lateinit var curfewWeekdayButton: Button
    private lateinit var curfewWeekendButton: Button
    private lateinit var policyContainer: LinearLayout
    private lateinit var optionContainer: LinearLayout
    private lateinit var optionsExplanation: TextView
    private lateinit var optionsFootnote: TextView

    /**
     * Consent for the VPN, needed only when drawbridge is *not* device owner —
     * which is every unprovisioned install, including the one a parent tries
     * before committing to a QR wipe. On a provisioned phone this never fires.
     */
    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                // Locking promises the filter is running. Sealing the screen
                // after the parent declined it would make that a lie they could
                // no longer check.
                toast(getString(R.string.lock_needs_filter))
                render()
                return@registerForActivityResult
            }
            DnsFilterService.requestStart(this)
            mintKey()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Checked before the layout is set as well as in onResume, so a locked
        // device does not flash the configuration on its way to the lock screen.
        if (parentKey.isLocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.root).applyScreenInsets()

        updateNotice = findViewById(R.id.updateNotice)
        disconnectContainer = findViewById(R.id.disconnectContainer)
        curfewSchedule = findViewById(R.id.curfewSchedule)
        curfewWeekdayButton = findViewById(R.id.curfewWeekdayButton)
        curfewWeekendButton = findViewById(R.id.curfewWeekendButton)
        policyContainer = findViewById(R.id.policyContainer)
        optionContainer = findViewById(R.id.optionContainer)
        optionsExplanation = findViewById(R.id.optionsExplanation)
        optionsFootnote = findViewById(R.id.optionsFootnote)

        bindLanguages(findViewById(R.id.languageField))

        findViewById<Button>(R.id.lockButton).setOnClickListener { confirmLock() }
        findViewById<Button>(R.id.updateButton).setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        if (isFinishing) return
        if (parentKey.isLocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        render()
    }

    /**
     * Removal lives in the overflow menu rather than on the screen.
     *
     * It is the only way off a managed device that does not involve wiping it,
     * so it has to exist — but it is a once-in-the-life-of-the-phone action and
     * does not deserve a button next to the one used every time.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionRefresh -> {
            refreshPolicy()
            true
        }

        R.id.actionRemove -> {
            startActivity(Intent(this, RemoveActivity::class.java))
            true
        }

        R.id.actionDiagnostics -> {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // --- Language ------------------------------------------------------------

    private fun bindLanguages(field: MaterialAutoCompleteTextView) {
        val labels = Languages.supported.map { getString(it.label) }.toTypedArray()
        field.setSimpleItems(labels)

        // Everything this field shows is derived from Languages.current() a few
        // lines down, so there is nothing about it worth preserving across a
        // recreation — and preserving it actively breaks the picker.
        //
        // Choosing a language calls AppCompatDelegate.setApplicationLocales,
        // which recreates the activity. The rebuilt field is bound correctly
        // below, and then onRestoreInstanceState puts the *previous* text back
        // through the filtering setText — the one case the comment below warns
        // about. By then the labels have been re-read in the new language, so
        // the old text usually matches nothing at all and the dropdown comes up
        // empty: every language after the first choice is unreachable. Not
        // saving the state is what keeps the binding below authoritative.
        field.isSaveEnabled = false

        val current = Languages.current()
        val index = Languages.supported.indexOfFirst { it.tag == current }
        // The second argument suppresses filtering; without it, setting the text
        // narrows the dropdown to the one entry that matches it.
        field.setText(labels[index], false)

        field.setOnItemClickListener { _, _, position, _ ->
            val tag = Languages.supported[position].tag
            if (tag != Languages.current()) {
                Languages.select(tag)
                // The keyguard message is a string the system stores, not a
                // resource it re-resolves, so it would otherwise stay in
                // whichever language it was written in — including its date.
                deviceOwner.updateLockScreenInfo()
            }
        }
    }

    // --- Rendering -----------------------------------------------------------

    /**
     * The device status this screen used to carry — ownership, the filter, the
     * policy version, the live restriction list — is gone. All of it is in
     * Diagnostics, which is where someone troubleshooting looks, and none of it
     * is something a parent configuring the phone acts on. The protected-since
     * date went with it for a better reason: it is a fact about the *lock*, so
     * it belongs on the lock screen, which is where it already was.
     */
    private fun render() {
        updateNotice.visibility =
            if (AppInstaller(this).availableSelfUpdate() != null) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            // The policy is loaded from disk asynchronously, so anything read
            // straight out of onResume would show the state before it arrived —
            // which is how the policy line came to say "no variants" on a
            // document that had two.
            withContext(Dispatchers.IO) { policy.ensureLoaded() }

            // Re-checked once the policy is really loaded: an update named by a
            // document still being read off disk would otherwise be missed on
            // the first render after a cold start.
            updateNotice.visibility =
                if (AppInstaller(this@MainActivity).availableSelfUpdate() != null) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

            renderDisconnect()
            renderPolicies()
            renderOptions()
        }
    }

    // --- Disconnect philosophy -----------------------------------------------

    /**
     * The three philosophies, above the policy because it is the larger
     * question: the policy says what the web may contain, this says whether the
     * phone reaches the web at all.
     *
     * Unlike the policies, these are not read from the signed document. They are
     * a property of this household — "offline at nine on weeknights" cannot be
     * signed by this project for somebody else's teenager — so the words come
     * from string resources and the choice is stored on the device.
     */
    private fun renderDisconnect() {
        disconnectContainer.removeAllViews()
        val current = disconnect.mode
        val inflater = LayoutInflater.from(this)

        for (choice in DisconnectChoice.entries) {
            val card = inflater.inflate(R.layout.item_policy, disconnectContainer, false)
                as MaterialCardView
            card.findViewById<TextView>(R.id.policyName).setText(choice.title)
            // No subtitle: the policies use it for "who this is for", and these
            // three are for everyone.
            card.findViewById<TextView>(R.id.policySubtitle).visibility = View.GONE
            card.findViewById<TextView>(R.id.policyDescription).setText(choice.description)
            card.findViewById<RadioButton>(R.id.policySelected).isChecked = choice.mode == current
            card.isChecked = choice.mode == current
            card.setOnClickListener { selectDisconnect(choice.mode) }
            disconnectContainer.addView(card)
        }

        curfewSchedule.visibility =
            if (current == DisconnectSettings.Mode.CURFEW) View.VISIBLE else View.GONE
        renderCurfewWindows()
    }

    private fun renderCurfewWindows() {
        curfewWeekdayButton.text = windowLabel(disconnect.weekdayWindow)
        curfewWeekendButton.text = windowLabel(disconnect.weekendWindow)
        curfewWeekdayButton.setOnClickListener {
            editWindow(disconnect.weekdayWindow) { disconnect.weekdayWindow = it }
        }
        curfewWeekendButton.setOnClickListener {
            editWindow(disconnect.weekendWindow) { disconnect.weekendWindow = it }
        }
    }

    private fun windowLabel(window: DisconnectSettings.Window): String =
        getString(R.string.curfew_window, window.start, window.end)

    /**
     * Asks for the two ends of one window, in order.
     *
     * A plain [TimePickerDialog] rather than the Material picker: this runs on
     * whatever handset a parent was given, and the platform dialog follows that
     * phone's own 12- or 24-hour setting without being told. The value stored is
     * always `HH:mm` regardless of how it was displayed, because the schedule is
     * compared against a wall clock rather than shown back to Android.
     */
    private fun editWindow(
        current: DisconnectSettings.Window,
        store: (DisconnectSettings.Window) -> Unit,
    ) {
        pickTime(R.string.curfew_pick_start, current.start) { start ->
            pickTime(R.string.curfew_pick_end, current.end) { end ->
                store(DisconnectSettings.Window(start, end))
                renderCurfewWindows()
                applyDisconnect()
            }
        }
    }

    private fun pickTime(titleRes: Int, initial: String, onPicked: (String) -> Unit) {
        val parts = initial.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 21
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(
            this,
            { _, pickedHour, pickedMinute ->
                onPicked("%02d:%02d".format(pickedHour, pickedMinute))
            },
            hour,
            minute,
            DateFormat.is24HourFormat(this),
        ).apply { setTitle(titleRes) }.show()
    }

    private fun selectDisconnect(mode: DisconnectSettings.Mode) {
        if (mode == disconnect.mode) return
        disconnect.mode = mode
        renderDisconnect()
        applyDisconnect()
        toast(applied(getString(choiceFor(mode).title), removed = 0, mayRemove = false))
    }

    /**
     * Enforcement is keyed on protection rather than on the lock — see
     * [CurfewController.applyIfProtected] — so on a phone that has never been
     * locked this records the choice and changes nothing, which is the same
     * contract every other control on this screen has.
     */
    private fun applyDisconnect() = CurfewController(this).applyIfProtected()

    private fun choiceFor(mode: DisconnectSettings.Mode): DisconnectChoice =
        DisconnectChoice.entries.first { it.mode == mode }

    /** The screen's words for each mode, kept next to the mode they describe. */
    private enum class DisconnectChoice(
        val mode: DisconnectSettings.Mode,
        val title: Int,
        val description: Int,
    ) {
        OFFLINE(
            DisconnectSettings.Mode.OFFLINE,
            R.string.disconnect_offline_name,
            R.string.disconnect_offline_description,
        ),
        ONLINE(
            DisconnectSettings.Mode.ONLINE,
            R.string.disconnect_online_name,
            R.string.disconnect_online_description,
        ),
        CURFEW(
            DisconnectSettings.Mode.CURFEW,
            R.string.disconnect_curfew_name,
            R.string.disconnect_curfew_description,
        ),
    }

    /**
     * Both selections are read off disk, so they are fetched on an IO thread
     * rather than from whichever callback happens to want them.
     */
    private suspend fun selectedPolicyId(): String? =
        withContext(Dispatchers.IO) { policy.selectedProfile?.id }

    private suspend fun enabledOptionIds(): Set<String> =
        withContext(Dispatchers.IO) { policy.enabledOptionIds }

    private suspend fun renderPolicies() {
        policyContainer.removeAllViews()

        val choices = policy.profiles
        if (choices.isEmpty()) {
            policyContainer.addView(placeholder(R.string.policy_none_available))
            return
        }

        val selected = selectedPolicyId()
        val language = Languages.current()
        val inflater = LayoutInflater.from(this)

        for (choice in choices) {
            val card = inflater.inflate(R.layout.item_policy, policyContainer, false)
                as MaterialCardView

            // The policy's own words come from the signed document, not from a
            // string resource, so they carry their translations with them.
            card.findViewById<TextView>(R.id.policyName).text = choice.displayName(language)
            card.bindOptionalText(R.id.policySubtitle, choice.displaySubtitle(language))
            card.bindOptionalText(R.id.policyDescription, choice.displayDescription(language))
            card.findViewById<RadioButton>(R.id.policySelected).isChecked = choice.id == selected
            card.isChecked = choice.id == selected

            card.setOnClickListener {
                if (choice.id != selected) confirmPolicy(choice)
            }
            policyContainer.addView(card)
        }
    }

    private suspend fun renderOptions() {
        optionContainer.removeAllViews()

        val options = policy.options
        val hasOptions = options.isNotEmpty()
        optionsExplanation.visibility = if (hasOptions) View.VISIBLE else View.GONE
        optionsFootnote.visibility = if (hasOptions) View.VISIBLE else View.GONE
        if (!hasOptions) {
            optionContainer.addView(placeholder(R.string.options_none_available))
            return
        }

        val enabled = enabledOptionIds()
        val language = Languages.current()
        val inflater = LayoutInflater.from(this)

        for (option in options) {
            val row = inflater.inflate(R.layout.item_option, optionContainer, false)

            row.findViewById<TextView>(R.id.optionName).text = label(option)
            row.bindOptionalText(R.id.optionDescription, option.displayDescription(language))

            val switch = row.findViewById<MaterialSwitch>(R.id.optionSwitch)
            val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
                onOptionToggled(option, switch, checked)
            }
            switch.isChecked = option.id in enabled
            switch.setOnCheckedChangeListener(listener)
            optionContainer.addView(row)
        }
    }

    /** "Allow WhatsApp  14+" — the age sits with the name, not in the body text. */
    private fun label(option: PolicyOption): String {
        val name = option.displayName(Languages.current())
        val age = option.recommendedAge ?: return name
        return "$name  ${getString(R.string.option_recommended_age, age)}"
    }

    private fun placeholder(textId: Int): TextView =
        TextView(this).apply {
            setText(textId)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }

    private fun View.bindOptionalText(id: Int, text: String) {
        findViewById<TextView>(id).apply {
            this.text = text
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    // --- Locking -------------------------------------------------------------

    private fun confirmLock() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_confirm_title)
            .setMessage(R.string.lock_confirm_message)
            .setPositiveButton(R.string.lock_confirm_yes) { _, _ -> lockDevice() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Applies the restrictions, starts the filter, and only then mints the key.
     *
     * Order matters: everything that needs this screen has to be done before the
     * screen is sealed, and the VPN-consent dialog is the one step that can come
     * back later — hence [vpnConsent] finishing the job.
     */
    private fun lockDevice() {
        // The only place the lockdown is applied unconditionally. Everywhere else
        // goes through reapplyIfProtected, which does nothing until this has
        // happened once — locking is what turns enforcement on, not provisioning
        // and not opening the app.
        deviceOwner.applyManagedDevicePolicy()

        // Herald is normally already here, fetched at provisioning so the parent
        // could move their bookmarks across before locking. This run is the
        // catch-up: a phone that was provisioned on a metered connection, or that
        // failed the download, gets another go at the moment somebody is standing
        // over it waiting.
        DrawbridgeApplication.fetchPolicyAndRequiredApps(this)

        requestBatteryOptimisationExemption()

        // Device Owner is consent enough for the VPN. Anything else has to ask.
        val consent = DnsFilterService.requestStart(this)
        if (consent != null) {
            vpnConsent.launch(consent)
            return
        }
        mintKey()
    }

    private fun mintKey() {
        startActivity(LockActivity.mintKey(this))
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

    // --- Policy and options --------------------------------------------------

    /**
     * The confirmation is not ceremony. Applying a policy runs the app blocker
     * immediately, and an app it does not allow is uninstalled — choosing the
     * looser one again will not bring it back.
     */
    private fun confirmPolicy(choice: Profile) {
        AlertDialog.Builder(this)
            .setTitle(
                getString(R.string.policy_confirm_title, choice.displayName(Languages.current())),
            )
            .setMessage(R.string.policy_confirm_message)
            .setPositiveButton(R.string.policy_confirm_apply) { _, _ -> applyPolicy(choice) }
            // Nothing to undo on cancel: the cards are only ever checked from
            // the stored selection, so tapping one does not move the tick.
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPolicy(choice: Profile) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.policy_applying_title)
            .setMessage(R.string.policy_applying_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            // selectProfile syncs the blocklists before swapping the filter, so
            // the app sweep below runs against the policy that is actually in
            // force rather than the one being replaced.
            val selected = policy.selectProfile(choice.id)
            val removed = if (selected) sweep() else 0
            if (selected) SelectionProvider.notifyChanged(this@MainActivity)

            progress.dismiss()
            if (selected) {
                toast(applied(choice.displayName(Languages.current()), removed, mayRemove = true))
            } else {
                toast(getString(R.string.policy_apply_failed))
            }
            render()
        }
    }

    /**
     * Switching an option on only ever widens what is allowed, so it applies
     * straight away. Switching one off takes something back, and taking an app
     * back means uninstalling it — so that direction asks first.
     */
    private fun onOptionToggled(option: PolicyOption, view: CompoundButton, checked: Boolean) {
        if (checked) {
            applyOption(option, true)
            return
        }

        // Putting the switch back has to go through restore(), not through
        // isChecked: assigning it fires this listener again, and cancelling
        // "turn it off" would then be read as switching it on.
        val restore = { view.setCheckedWithoutFiring(true) { c -> onOptionToggled(option, view, c) } }

        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.option_disable_confirm_title,
                    option.displayName(Languages.current()),
                ),
            )
            .setMessage(R.string.option_disable_confirm_message)
            .setPositiveButton(R.string.option_disable_confirm_yes) { _, _ ->
                applyOption(option, false)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> restore() }
            .setOnCancelListener { restore() }
            .show()
    }

    private fun applyOption(option: PolicyOption, enabled: Boolean) {
        lifecycleScope.launch {
            if (!policy.setOptionEnabled(option.id, enabled)) {
                toast(getString(R.string.option_change_failed))
                renderOptions()
                return@launch
            }

            // herald filters on this same choice, and hears about it here or
            // not at all until its next daily poll.
            SelectionProvider.notifyChanged(this@MainActivity)

            // No blocklist changes with an option, so there is nothing to
            // download and nothing to wait for — but an option turned *off*
            // leaves an app on the device that policy no longer allows.
            val removed = if (enabled) 0 else sweep()
            toast(applied(option.displayName(Languages.current()), removed, mayRemove = !enabled))
            renderOptions()
        }
    }

    private fun CompoundButton.setCheckedWithoutFiring(
        checked: Boolean,
        listener: (Boolean) -> Unit,
    ) {
        setOnCheckedChangeListener(null)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> listener(value) }
    }

    /**
     * Removes what the current selection no longer allows — which, from this
     * screen, is now always nothing.
     *
     * The configuration screen only exists while the phone is unlocked: before
     * the first lock, or after the parent has spent their key. Removal is keyed
     * on the lock, so a sweep started here declines every package and the honest
     * thing to tell the parent is that their change lands when they lock.
     *
     * **The gate here used to be `protectedSince != 0`, and that was the bug.**
     * It reads as "has ever been locked" and stays true forever afterwards, so
     * unlocking never reopened the window it was supposed to protect. The draft
     * behaviour it was written for — comparing two policies without having apps
     * uninstalled by the act of looking — is now what happens at every unlock,
     * not just before the first lock.
     *
     * The call is kept rather than deleted because it is the truthful place for
     * the count to come from if the gate ever moves again.
     */
    private suspend fun sweep(): Int {
        if (!parentKey.isLocked) return 0
        return withContext(Dispatchers.IO) { AppBlocker(this@MainActivity).sweep().size }
    }

    /**
     * What to tell the parent after a change.
     *
     * Three cases rather than one, because removal follows the lock and this
     * screen only exists while the phone is unlocked. [mayRemove] is the caller's
     * knowledge of whether the change is the kind that takes apps away at all —
     * switching an option *on* never does, so promising a removal at the next
     * lock would be a lie in the other direction.
     */
    private fun applied(name: String, removed: Int, mayRemove: Boolean): String = when {
        removed > 0 ->
            resources.getQuantityString(R.plurals.change_applied, removed, name, removed)
        mayRemove -> getString(R.string.change_applied_at_lock, name)
        else -> getString(R.string.change_applied_plain, name)
    }

    private fun refreshPolicy() {
        lifecycleScope.launch {
            val message = when (val outcome = policy.refresh(userInitiated = true)) {
                is PolicyManager.RefreshOutcome.Success ->
                    getString(R.string.policy_refreshed, outcome.version)
                is PolicyManager.RefreshOutcome.Failure ->
                    getString(R.string.policy_refresh_failed)
            }
            toast(message)
            render()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
