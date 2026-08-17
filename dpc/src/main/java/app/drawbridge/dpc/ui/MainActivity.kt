package app.drawbridge.dpc.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.BuildConfig
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.admin.ProvisioningLog
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.apps.BrowserSettings
import app.drawbridge.dpc.apps.InstallLockSettings
import app.drawbridge.dpc.curfew.CurfewController
import app.drawbridge.dpc.curfew.DisconnectSettings
import app.drawbridge.dpc.policy.SelectionProvider
import app.drawbridge.dpc.security.LockTimer
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
    private val browsers by lazy { BrowserSettings(this) }
    private val installLock by lazy { InstallLockSettings(this) }
    private val lockTimer by lazy { LockTimer(this) }

    private lateinit var updateNotice: View
    private lateinit var disconnectContainer: LinearLayout
    private lateinit var curfewSchedule: LinearLayout
    private lateinit var curfewWeekdayButton: Button
    private lateinit var curfewWeekendButton: Button
    private lateinit var browserContainer: LinearLayout
    private lateinit var installLockSwitch: MaterialSwitch
    private lateinit var lockTimerSwitch: MaterialSwitch
    private lateinit var lockTimerLength: View
    private lateinit var lockTimerField: MaterialAutoCompleteTextView
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
        browserContainer = findViewById(R.id.browserContainer)
        installLockSwitch = findViewById(R.id.installLockSwitch)
        lockTimerSwitch = findViewById(R.id.lockTimerSwitch)
        lockTimerLength = findViewById(R.id.lockTimerLength)
        lockTimerField = findViewById(R.id.lockTimerField)
        // Here rather than in renderLockTimer, so it is off before anything can be
        // saved: the stored length is the only truth this field has, and a
        // restored text going back through the filtering setText is what empties
        // the dropdown after a recreation. Same trap as bindLanguages below.
        lockTimerField.isSaveEnabled = false
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
            renderBrowsers()
            renderInstallLock()
            renderLockTimer()
            renderPolicies()
            renderOptions()
        }
    }

    // --- The lock timer ------------------------------------------------------

    /**
     * Whether this lock ends by itself, and after how long.
     *
     * **This is the only control on the screen that does not wait for the lock,
     * because it *is* part of the lock.** Everything above records a preference
     * that lands when the phone is sealed; this records what sealing will mean.
     * The countdown itself starts in [LockActivity.sealWithKey], for the same
     * reason the key is committed there rather than when it is shown: a reveal
     * somebody walks away from must leave the phone exactly as it was, timer
     * included.
     *
     * Nothing here is a weakening of the key. A timed lock mints and shows a key
     * exactly as an untimed one does, and typing it in still opens the phone at
     * any moment — which also cancels the timer. What the timer changes is only
     * what happens if nobody ever types it.
     */
    private fun renderLockTimer() {
        findViewById<View>(R.id.root).bindInfo(
            R.id.lockTimerInfo,
            title = getString(R.string.lock_timer_name),
            body = getString(R.string.lock_timer_info),
        )

        val labels = LockTimer.Length.entries.map { getString(it.label) }.toTypedArray()
        lockTimerField.setSimpleItems(labels)
        // The second argument suppresses filtering; without it, setting the text
        // narrows the list to the one entry that matches it.
        lockTimerField.setText(getString(lockTimer.length.label), false)
        lockTimerField.setOnItemClickListener { _, _, position, _ ->
            val chosen = LockTimer.Length.entries[position]
            if (chosen != lockTimer.length) {
                lockTimer.length = chosen
                toast(getString(R.string.lock_timer_applied, getString(chosen.label)))
            }
        }

        // Set before the listener is attached, so showing the stored value does
        // not read as the parent having just toggled it.
        lockTimerSwitch.setOnCheckedChangeListener(null)
        lockTimerSwitch.isChecked = lockTimer.isEnabled
        lockTimerLength.visibility = if (lockTimer.isEnabled) View.VISIBLE else View.GONE
        lockTimerSwitch.setOnCheckedChangeListener { _, checked ->
            lockTimer.isEnabled = checked
            lockTimerLength.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                toast(getString(R.string.lock_timer_applied, getString(lockTimer.length.label)))
            } else {
                toast(getString(R.string.lock_timer_off_applied))
            }
        }
    }

    // --- The install lock ----------------------------------------------------

    /**
     * Whether the phone closes at the lock: no new apps after it, and updates of
     * the apps already there still coming through.
     *
     * Device-local like the browser policy and the disconnect philosophy, and
     * **off by default** unlike either — it changes what the phone *is* rather
     * than what it filters, so nobody should get it by leaving a button
     * unpressed. See [InstallLockSettings].
     *
     * Nothing happens here beyond recording the choice. The restriction is keyed
     * on the lock, so it lands when [LockActivity.sealWithKey] re-applies the
     * restriction set, and the closed set is recorded by the same lock. This
     * screen only exists while the phone is unlocked, which is the state in which
     * this setting does nothing at all — and that is the point of it: the unlock
     * window is where a parent installs what the phone should have.
     */
    private fun renderInstallLock() {
        // The same ⓘ the policy and the options use, bound off the root rather
        // than off a card: this control is written into the layout instead of
        // being inflated from the document, so there is no card to hang it on.
        findViewById<View>(R.id.root).bindInfo(
            R.id.installLockInfo,
            title = getString(R.string.install_lock_name),
            body = getString(R.string.install_lock_info),
        )

        // Set before the listener is attached, so showing the stored value does
        // not read as the parent having just toggled it — the same order
        // renderOptions uses for the policy's own switches.
        installLockSwitch.setOnCheckedChangeListener(null)
        installLockSwitch.isChecked = installLock.isEnabled
        installLockSwitch.setOnCheckedChangeListener { _, checked ->
            installLock.isEnabled = checked
            toast(applied(getString(R.string.install_lock_name)))
        }
    }

    // --- Browser choice ------------------------------------------------------

    /**
     * How much browser this phone has: every sanctioned one, herald mono alone,
     * or none.
     *
     * Device-local like the disconnect philosophy and for the same reason — the
     * signed document says which browsers are *safe*, this says how many of the
     * safe ones a household wants — so the words are string resources rather
     * than the document's.
     *
     * **The description is the browser icons themselves**, drawn from the
     * launcher icons of the browsers actually on this phone. "All the allowed
     * browsers" is a claim to take on trust; four icons somebody recognises is
     * the same claim, checkable, and it stays true when the policy's list changes
     * without anyone rewriting a string.
     */
    private fun renderBrowsers() {
        browserContainer.removeAllViews()
        val current = browsers.choice
        val policyDocument = policy.policy.value
        val inflater = LayoutInflater.from(this)

        for (choice in BrowserChoice.entries) {
            val card = inflater.inflate(R.layout.item_browser, browserContainer, false)
                as MaterialCardView
            card.findViewById<TextView>(R.id.browserName).setText(choice.title)
            card.bindInfo(
                R.id.browserInfo,
                title = getString(choice.title),
                body = getString(choice.description),
            )
            card.bindBrowserIcons(
                BrowserSettings.allowedBrowsers(policyDocument, choice.choice),
            )
            card.findViewById<RadioButton>(R.id.browserSelected).isChecked =
                choice.choice == current
            card.isChecked = choice.choice == current
            card.setOnClickListener { selectBrowsers(choice.choice) }
            browserContainer.addView(card)
        }

    }

    /**
     * One icon per browser the *policy* allows under this choice, or the
     * prohibition sign when it allows none.
     *
     * **Every allowed browser, not merely the installed ones**, which is the
     * distinction that matters and the one this got wrong first. The row answers
     * "what does this choice allow" — a question about the policy — and a phone
     * that happens not to have Vivaldi on it does not make Vivaldi any less
     * allowed. Showing only what is installed made the answer depend on the
     * device, so the same choice looked different on two phones and looked
     * *smaller* than it is on a phone that had just had browsers removed by the
     * choice above it.
     *
     * The pictures are static for the same reason — see [iconOf].
     */
    private fun View.bindBrowserIcons(packages: Set<String>) {
        val row = findViewById<LinearLayout>(R.id.browserIcons)
        row.removeAllViews()
        row.contentDescription = getString(R.string.browser_icons_description)

        if (packages.isEmpty()) {
            row.addView(
                browserIcon(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_no_browser)),
            )
            return
        }
        packages.sorted().forEach { row.addView(browserIcon(iconOf(it))) }
    }

    /**
     * A browser's icon: a bundled picture, or a globe for one this build has
     * never heard of.
     *
     * **Deliberately not the installed app's own launcher icon**, which is what
     * this did first and is a worse idea for the reason the whole row exists.
     * These illustrate the *policy's* list — they are pictures of products, not
     * a report on the device — and reading them off the phone made the same
     * choice look different on two handsets, themed icons and OEM restyling
     * included. A phone is also perfectly capable of not having the app at all,
     * which is the ordinary case for Vivaldi and Focus.
     *
     * Being a little out of date if a vendor rebrands is the whole cost, and it
     * is a cost worth paying for a row that looks the same everywhere.
     */
    private fun iconOf(packageName: String): Drawable? =
        ContextCompat.getDrawable(this, BUNDLED_BROWSER_ICONS[packageName] ?: FALLBACK_ICON)

    private fun browserIcon(drawable: Drawable?): ImageView =
        ImageView(this).apply {
            setImageDrawable(drawable)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val size = resources.getDimensionPixelSize(R.dimen.browser_icon)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = size / 4 }
        }

    /**
     * Records the choice and reports where it lands.
     *
     * Nothing is removed here even though this screen can only be open while
     * unlocked: a browser the *chooser* narrows away is a reversible preference,
     * so it waits for the lock exactly as an option's apps do. See
     * [AppBlocker.deferred].
     *
     * The default handler is applied straight away regardless, because it takes
     * nothing away — it only decides which of the browsers already on the phone
     * inherits a tapped link.
     */
    private fun selectBrowsers(choice: BrowserSettings.Choice) {
        browsers.choice = choice
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { restoreNewlyAllowed() }
            toast(applied(getString(BrowserChoice.of(choice).title)))
            renderBrowsers()
        }
    }

    private enum class BrowserChoice(
        val choice: BrowserSettings.Choice,
        val title: Int,
        val description: Int,
    ) {
        ALL(
            BrowserSettings.Choice.ALL,
            R.string.browser_all_name,
            R.string.browser_all_description,
        ),
        MONO(
            BrowserSettings.Choice.MONO_ONLY,
            R.string.browser_mono_name,
            R.string.browser_mono_description,
        ),
        NONE(
            BrowserSettings.Choice.NONE,
            R.string.browser_none_name,
            R.string.browser_none_description,
        ),
        ;

        companion object {
            fun of(choice: BrowserSettings.Choice): BrowserChoice =
                entries.first { it.choice == choice }
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
            val card = inflater.inflate(R.layout.item_disconnect, disconnectContainer, false)
                as MaterialCardView
            card.findViewById<TextView>(R.id.disconnectName).setText(choice.title)
            // **These three keep their explanation on the card**, unlike the
            // policy and the options. One short line each rather than a
            // paragraph, and the name alone does not carry the meaning: "curfew
            // for the internet" does not say the clock gets locked, and
            // "blissfully offline" does not say calls and SMS still work.
            card.findViewById<TextView>(R.id.disconnectDescription).setText(choice.description)
            card.findViewById<ImageView>(R.id.disconnectSymbol).setImageResource(choice.symbol)
            card.findViewById<RadioButton>(R.id.disconnectSelected).isChecked =
                choice.mode == current
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

    /**
     * It says the same sentence every other control on this screen says, and it
     * used to say nothing at all.
     *
     * The old reasoning was that the radio moving is feedback enough, and that
     * "applied" would be a lie because the choice waits for the lock. The first
     * half was right about the radio and wrong about what a parent needs from
     * it: a tick that moves says the app heard you, not that the phone has
     * changed. The second half is answered by saying *when* rather than by
     * saying nothing.
     */
    private fun selectDisconnect(mode: DisconnectSettings.Mode) {
        if (mode == disconnect.mode) return
        disconnect.mode = mode
        renderDisconnect()
        applyDisconnect()
        DisconnectChoice.entries.firstOrNull { it.mode == mode }
            ?.let { toast(applied(getString(it.title))) }
    }

    /**
     * Enforcement is keyed on protection rather than on the lock — see
     * [CurfewController.applyIfProtected] — so on a phone that has never been
     * locked this records the choice and changes nothing, which is the same
     * contract every other control on this screen has.
     */
    private fun applyDisconnect() = CurfewController(this).apply()

    /** The screen's words for each mode, kept next to the mode they describe. */
    private enum class DisconnectChoice(
        val mode: DisconnectSettings.Mode,
        val title: Int,
        val description: Int,
        val symbol: Int,
    ) {
        OFFLINE(
            DisconnectSettings.Mode.OFFLINE,
            R.string.disconnect_offline_name,
            R.string.disconnect_offline_description,
            R.drawable.ic_disconnect_lotus,
        ),
        ONLINE(
            DisconnectSettings.Mode.ONLINE,
            R.string.disconnect_online_name,
            R.string.disconnect_online_description,
            R.drawable.ic_disconnect_robot,
        ),
        CURFEW(
            DisconnectSettings.Mode.CURFEW,
            R.string.disconnect_curfew_name,
            R.string.disconnect_curfew_description,
            R.drawable.ic_disconnect_moon,
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
            // The same shield the options carry. It used to be "(+14)" inside the
            // subtitle sentence, spelled three different ways across the
            // translations, which is the one number somebody compares profiles by.
            card.bindRating(
                R.id.policyRating,
                R.id.policyRatingShield,
                R.id.policyRatingText,
                age = choice.recommendedAge,
            )
            card.bindInfo(
                R.id.policyInfo,
                title = choice.displayName(language),
                body = choice.displayDescription(language),
            )
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

            row.findViewById<TextView>(R.id.optionName).text = option.displayName(language)
            row.bindRating(
                R.id.optionRating,
                R.id.optionRatingShield,
                R.id.optionRatingText,
                age = option.recommendedAge,
                variousAges = option.variousAges,
            )
            row.bindInfo(
                R.id.optionInfo,
                title = option.displayName(language),
                body = option.displayDescription(language),
            )

            val switch = row.findViewById<MaterialSwitch>(R.id.optionSwitch)
            // Checked before the listener is attached, so rendering the stored
            // selection does not read as the parent having just toggled it.
            switch.isChecked = option.id in enabled
            switch.setOnCheckedChangeListener { _, checked -> applyOption(option, checked) }
            optionContainer.addView(row)
        }
    }

    /**
     * The shield beside an option: its recommended age, or an advisory mark for
     * an option that has no single age.
     *
     * **It used to be "Allow Telegram  18+", trailing the name in running
     * text.** A number in a sentence is something to read; a filled shield in a
     * colour band is something to recognise, which is what somebody scanning
     * four options is actually doing. Same information, one glance instead of
     * one reading.
     *
     * **Drawn rather than borrowed, and that is a claim as much as a design
     * choice** — see colors.xml. PEGI, Kijkwijzer and the Parental Advisory
     * label are licensed marks that mean *those* bodies graded *this* content,
     * and none of them has been near WhatsApp or Telegram on anyone's behalf.
     * The footnote under these options already says where the ages come from,
     * and a borrowed badge would contradict it without a word being written.
     *
     * The bands are advice, so they are deliberately coarse: under 16, 16 and
     * 17, and 18 or over. An option carrying neither an age nor an advisory gets
     * no shield rather than an empty one.
     */
    private fun View.bindRating(
        containerId: Int,
        shieldId: Int,
        textId: Int,
        age: Int?,
        variousAges: Boolean = false,
    ) {
        val shield = findViewById<View>(containerId)

        // Fill and label together, because they are not independent: the yellow
        // band is the one that cannot carry white text.
        val (fill, label) = when {
            age != null && age >= 18 -> R.color.rating_high to R.color.rating_label
            age != null && age >= 16 -> R.color.rating_mid to R.color.rating_label
            age != null -> R.color.rating_low to R.color.rating_label_dark
            // The same orange as 16+, on purpose: these two are told apart by
            // the glyph rather than the hue, and "various" is neither a 14 nor
            // an adult rating.
            variousAges -> R.color.rating_mid to R.color.rating_label
            else -> {
                shield.visibility = View.GONE
                return
            }
        }

        shield.visibility = View.VISIBLE
        findViewById<ImageView>(shieldId)
            .setColorFilter(ContextCompat.getColor(this@MainActivity, fill))
        // "18+" rather than "18": the number alone reads as *at* that age, and
        // the whole point is that it is a floor. An exclamation mark where there
        // is no single age to print — the shield says "look at this one" and the
        // content description spells out what it means.
        findViewById<TextView>(textId).apply {
            text = if (age != null) getString(R.string.option_age_shield, age) else "!"
            setTextColor(ContextCompat.getColor(this@MainActivity, label))
        }
        shield.contentDescription = if (age != null) {
            getString(R.string.option_recommended_age_description, age)
        } else {
            getString(R.string.option_various_ages_description)
        }
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

    /**
     * Puts a policy's or an option's paragraph behind the ⓘ next to it.
     *
     * **The words are not cut, only moved** — they are the signed document's own
     * explanation of what a choice does, they are translated with it, and a
     * parent deciding between two policies needs them. What they were not worth
     * is being on screen all at once: four options and a policy carried around
     * 1,600 characters between them, above the Lock button, every time the screen
     * opened.
     *
     * An option with nothing to say gets no button rather than an empty dialog,
     * which is the same rule [bindOptionalText] applies to a missing subtitle.
     */
    private fun View.bindInfo(id: Int, title: String, body: String) {
        findViewById<ImageButton>(id).apply {
            if (body.isBlank()) {
                visibility = View.GONE
                return
            }
            visibility = View.VISIBLE
            contentDescription = getString(R.string.info_button_description, title)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    // --- Locking -------------------------------------------------------------

    /**
     * The last sentence of [R.string.lock_confirm_message] says the key is the
     * only way back short of a factory reset, and with a timer running that is not
     * true — so the dialog says what is true instead. A confirmation that
     * overstates what it is about to do is worse than none.
     */
    private fun confirmLock() {
        val message = buildString {
            append(getString(R.string.lock_confirm_message))
            if (lockTimer.isEnabled) {
                append("\n\n")
                append(getString(R.string.lock_confirm_timer, getString(lockTimer.length.label)))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_confirm_title)
            .setMessage(message)
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
            if (selected) {
                restoreNewlyAllowed()
                // Real work again since 2026-08-15: the policy's own list acts
                // whether the phone is locked or not, and this screen is the one
                // place a parent changes that list.
                Log.i(TAG, "Policy ${choice.id} applied; ${sweep()} packages removed")
                SelectionProvider.notifyChanged(this@MainActivity)
            }

            progress.dismiss()
            if (selected) {
                // Not "after lock": the removals this just did have happened.
                toast(getString(R.string.change_applied, choice.displayName(Languages.current())))
            } else {
                toast(getString(R.string.policy_apply_failed))
            }
            render()
        }
    }

    /**
     * Both directions apply as soon as the switch moves.
     *
     * Switching one off used to ask first, because it used to uninstall the
     * apps the option allowed the moment it was answered. Removal follows the
     * lock now and this screen only exists while the phone is unlocked, so the
     * dialog was warning about something that no longer happens — and its
     * second sentence, that switching back on does not bring the apps back, had
     * stopped being true as well: [restoreNewlyAllowed] unhides what the policy
     * names again. The toast says where the change actually lands.
     */
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
            // download and nothing to wait for. An option switched *on* can
            // un-block a package that is currently hidden, which is the half
            // that used to need a reboot.
            //
            // An option switched *off* still takes nothing away here, and that
            // is deliberate rather than left over: what an option covers is the
            // one category that still waits for the lock, precisely because the
            // parent may be mid-decision and an uninstall cannot be undone. The
            // sweep runs anyway as reconciliation — it is what removes anything
            // else that has drifted onto the phone — and the toast says where
            // this particular change lands.
            if (enabled) restoreNewlyAllowed()
            Log.i(TAG, "Option ${option.id} set to $enabled; ${sweep()} packages removed")
            toast(applied(option.displayName(Languages.current())))
            renderOptions()
        }
    }

    /**
     * Removes what the current selection no longer allows.
     *
     * **The `isLocked` gate here is gone, as of 2026-08-15, and that is the
     * point of the change rather than an oversight.** This screen only exists
     * while the phone is unlocked, so a sweep from here used to decline every
     * package by construction — which is why the count it returns had stopped
     * meaning anything. Now the policy's own list acts in either state, so a
     * sweep started here really does remove things, and the count is real again.
     *
     * What it still will not touch is anything an option covers: those wait for
     * the lock, so switching *Allow WhatsApp* off on an unlocked phone changes
     * the setting and takes nothing away until the parent locks. That is the
     * distinction [R.string.settings_take_effect_at_lock] describes above the
     * controls, and the reason the toast still speaks of the lock.
     *
     * **The gate before that one was `protectedSince != 0`, and it was a bug.**
     * It reads as "has ever been locked" and stays true forever afterwards, so
     * unlocking never reopened the window it was supposed to protect.
     */
    private suspend fun sweep(): Int =
        withContext(Dispatchers.IO) {
            AppBlocker(this@MainActivity).sweep()
                .count { it.value != AppBlocker.Action.FAILED }
        }

    /**
     * Brings back anything the change just started allowing.
     *
     * Unlike [sweep] this is **not** gated on the lock, because it only ever
     * adds. Switching *Allow YouTube* on used to leave a hidden YouTube hidden
     * until the next reboot, since the only caller of the restore was a sweep
     * the configuration screen skips while unlocked — which is exactly the state
     * this screen is always in.
     */
    private suspend fun restoreNewlyAllowed() = withContext(Dispatchers.IO) {
        runCatching { AppBlocker(this@MainActivity).restoreNowAllowed() }
    }

    /**
     * What to tell the parent after a change: one sentence, for every control on
     * this screen.
     *
     * It used to be three, distinguishing what went now from what waited. That
     * distinction is not one a parent has to hold: nothing here lands until the
     * phone is locked, which is what the owner watched on the reference phone on
     * 2026-08-14 with the streaming option and Disney+. So the screen says the
     * one thing that is true of all of it, and
     * [R.string.settings_take_effect_at_lock] above the controls says why.
     *
     * The removal count is gone with them. It could only ever have read zero
     * from here — [sweep] declines while unlocked, and unlocked is the only
     * state this screen exists in.
     */
    private fun applied(name: String): String =
        getString(R.string.change_applied_at_lock, name)

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

    private companion object {
        const val TAG = "MainActivity"

        /**
         * A picture for each browser the policy allows.
         *
         * **Third-party marks, used to identify the products they belong to**,
         * which is what a browser picker does and is a different thing from the
         * rating shields — those are drawn here precisely *because* borrowing
         * PEGI's mark would have claimed PEGI graded something. Naming Chrome
         * with Chrome's icon claims only that this is Chrome, which is true.
         *
         * Provenance, since this is a public repository:
         *
         *  - `browser_chrome` — *Google Chrome icon (February 2022)*, Wikimedia
         *    Commons. Google Chrome and its logo are trademarks of Google LLC.
         *  - `browser_focus` — *Firefox Focus 2021 Icon*, Wikimedia Commons,
         *    CC BY. Firefox Focus and its logo are trademarks of the Mozilla
         *    Foundation.
         *  - `browser_vivaldi` — *Vivaldi web browser logo*, Wikimedia Commons,
         *    CC BY 4.0, attributed to Vivaldi Technologies.
         *  - `browser_herald`, `browser_herald_mono` — this project's own,
         *    downscaled from `site/assets/img/`.
         */
        val BUNDLED_BROWSER_ICONS = mapOf(
            "app.drawbridge.herald" to R.drawable.browser_herald,
            BrowserSettings.MONO_PACKAGE to R.drawable.browser_herald_mono,
            "com.android.chrome" to R.drawable.browser_chrome,
            "org.mozilla.focus" to R.drawable.browser_focus,
            "com.vivaldi.browser" to R.drawable.browser_vivaldi,
        )

        val FALLBACK_ICON = R.drawable.ic_browser_generic
    }
}
