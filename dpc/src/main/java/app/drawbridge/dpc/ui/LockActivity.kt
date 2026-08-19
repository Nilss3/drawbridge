package app.drawbridge.dpc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.curfew.CurfewController
import app.drawbridge.dpc.curfew.DisconnectSettings
import app.drawbridge.dpc.security.LockTimer
import app.drawbridge.dpc.security.LockTimerController
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.dpc.update.AppInstaller
import app.drawbridge.policy.PolicyManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * The lock, in its two moments.
 *
 * **Reveal** happens once, when the parent locks the device: the key is
 * generated here and shown here, and this is the only time it exists in a form
 * anyone can read. Deciding to keep no copy of it is allowed — it is a
 * legitimate way to make the configuration permanent on purpose — but it is
 * only ever reached by pressing **Done**, which is the button that says it
 * seals, behind a checkbox that says the key was written down.
 *
 * Nothing is stored until Done says so. The device is sealed by [sealWithKey]
 * and by nothing else, so a reveal that is walked away from leaves the phone as
 * it was.
 *
 * **Back leaves, and that is all it does.** It used to open a dialog whose
 * confirming button called [sealWithKey], so the way out of a screen saying
 * "write this down" was an offer to lock the phone forever with the key you had
 * just declined to keep — while the same screen promised, in a line since cut
 * for length, that leaving early forgets the key and locks nothing. Two claims,
 * one screen, and the destructive one was on the button every Android user
 * presses to go back. The behaviour is unchanged now that the line is gone:
 * leaving still forgets the key and seals nothing.
 *
 * **Challenge** is every time after that: the key, typed back, opens the
 * configuration screen. There is no attempt limit. A six-digit PIN needed one; a
 * hundred-bit key does not, and a lockout on the only way in is a way to strand
 * the parent for half an hour with nothing else to try.
 *
 * **And the key is no longer the only way out.** [LockTimer] can end a lock on a
 * clock: a period chosen on the configuration screen and armed here at the seal,
 * or the thirty days the code-forgotten door in the overflow menu offers to
 * whoever is holding a phone whose key is gone. Both doors are the same door —
 * they write a deadline, and [LockTimerController] opens it — and both are
 * cancelled by the ordinary unlock below, because typing the key in ends the lock
 * the deadline belonged to.
 */
class LockActivity : AppCompatActivity() {

    private val parentKey by lazy { ParentKey(this) }
    private val lockTimer by lazy { LockTimer(this) }

    /**
     * Non-null only while revealing.
     *
     * Kept as state rather than read back off the TextView because a TextView
     * does not save its own text: rotating the phone during the reveal would
     * have dropped the only copy of a key that had already been committed to,
     * leaving the device locked and unopenable.
     */
    private var revealedKey: String? = null

    /** Posts the one deadline check this screen makes for itself; see [scheduleInPlaceExpiry]. */
    private val handler = Handler(Looper.getMainLooper())
    private var expiryCheck: Runnable? = null

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
        if (revealing) {
            showTimerToCome()
        } else {
            showLockHistory()
            showRunningTimer()
            showCurrentSettings()
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
                    // Leaving the reveal is just leaving: the key was never
                    // committed, so the phone stays filtered, unlocked and
                    // configurable, and locking again mints a new one.
                    //
                    // The configuration screen has to be started rather than
                    // returned to, because it finishes itself on the way here so
                    // that a sealed phone cannot have it waiting in the back
                    // stack. Without this, back from the reveal lands on the
                    // launcher — which is not what back means.
                    //
                    // Backing out of the *challenge* closes drawbridge rather
                    // than returning to whatever was under it.
                    if (revealing) {
                        startActivity(Intent(this@LockActivity, MainActivity::class.java))
                        finish()
                    } else {
                        finishAffinity()
                    }
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KEY, revealedKey)
    }

    /**
     * Leaves if the lock is gone, and arranges to notice if it goes while this
     * screen is on top.
     *
     * **Reported from the Moto on 2026-08-17, the first time a timer ran out for
     * real.** The unlock itself worked — the keyguard said so — but drawbridge,
     * still in the background on this screen, went on asking for a key that no
     * longer existed. Killing it and reopening fixed it. The mirror of this check
     * has always been in [MainActivity.onResume], which forwards to *this* screen
     * when the phone is locked; the way back was never written, because until the
     * timer there was no way for a phone to unlock without somebody standing in
     * front of this activity doing it.
     *
     * The controller is asked first rather than trusted to have run. It is
     * idempotent, it costs two preference reads when nothing is due, and it means
     * a phone whose alarm was lost unlocks the moment a parent opens the app
     * instead of at the next hourly pass.
     */
    override fun onResume() {
        super.onResume()
        if (revealedKey != null) return

        LockTimerController(this).apply()
        if (!parentKey.isLocked) {
            leaveForConfiguration()
            return
        }
        scheduleInPlaceExpiry()
    }

    override fun onPause() {
        super.onPause()
        expiryCheck?.let { handler.removeCallbacks(it) }
        expiryCheck = null
    }

    /**
     * Watches the deadline pass without leaving the screen.
     *
     * [onResume] covers the ordinary case — the phone unlocked while nobody was
     * looking, and somebody comes back to it. This covers the case a tester
     * creates and a child would too: sitting on the lock screen *as* the deadline
     * arrives. Without it the screen would keep asking for a key for as long as
     * it stayed in front of somebody, which is exactly the moment it looks broken.
     *
     * One post rather than a poll, aimed at the deadline itself, with a second's
     * grace so the controller is not asked a tick early. Cancelled in [onPause],
     * so a backgrounded screen costs nothing.
     */
    private fun scheduleInPlaceExpiry() {
        expiryCheck?.let { handler.removeCallbacks(it) }
        expiryCheck = null

        if (!lockTimer.isArmed) return
        val untilExpiry = lockTimer.expiresAt - System.currentTimeMillis() + 1_000L
        if (untilExpiry <= 0) return

        val check = Runnable {
            LockTimerController(this).apply()
            if (!parentKey.isLocked) leaveForConfiguration()
        }
        expiryCheck = check
        handler.postDelayed(check, untilExpiry)
    }

    /**
     * The same hand-over [onBackPressedDispatcher] does on the reveal, and for the
     * same reason: this activity finishes MainActivity on its way in, so returning
     * to a configuration screen means starting one.
     */
    private fun leaveForConfiguration() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
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
    /**
     * What the phone is set to, on the screen it spends its life on.
     *
     * The dates above say when it was locked; these two say what it was locked
     * into. The curfew hours are the line that earns this: they are what gets
     * asked about at half past nine, and until now reading them cost the key —
     * which mints a new one, so checking a setting was a credential rotation.
     *
     * Both are read fresh rather than cached, because the policy name comes from
     * a document that changes under the phone.
     */
    private fun showCurrentSettings() {
        val policyLine = findViewById<TextView>(R.id.currentPolicy)
        val disconnectLine = findViewById<TextView>(R.id.currentDisconnect)

        if (parentKey.protectedSince <= 0) {
            policyLine.visibility = View.GONE
            disconnectLine.visibility = View.GONE
            return
        }

        // PolicyManager.selectedProfile already resolves the stored choice, then
        // the document's default, then nothing -- the same answer the
        // configuration screen shows, rather than a second reading of it.
        val profile = DrawbridgeApplication.policy(this).selectedProfile
        policyLine.text = getString(
            R.string.lock_current_policy,
            profile?.displayName(Languages.current())
                ?: getString(R.string.lock_current_policy_unnamed),
        )

        val settings = DisconnectSettings(this)
        disconnectLine.text = when (settings.mode) {
            DisconnectSettings.Mode.OFFLINE -> getString(R.string.lock_current_offline)
            DisconnectSettings.Mode.ONLINE -> getString(R.string.lock_current_online)
            DisconnectSettings.Mode.CURFEW -> getString(
                R.string.lock_current_curfew,
                settings.weekdayWindow.start,
                settings.weekdayWindow.end,
                settings.weekendWindow.start,
                settings.weekendWindow.end,
            )
        }
    }

    /**
     * On the reveal: that this lock will end by itself, and after how long.
     *
     * A period rather than a date, because the countdown starts at *Done* and not
     * at the moment this screen was drawn — a date computed here would be wrong by
     * however long the parent spent finding a pen, and this is the screen where
     * being wrong about the key's importance matters most.
     *
     * It is on the reveal at all because of what the timer changes about the
     * sentence above it. "Write this down or the phone stays like this forever" is
     * the reason anybody copies a twenty-character key, and with a timer running it
     * is no longer true.
     */
    private fun showTimerToCome() {
        val notice = findViewById<TextView>(R.id.revealTimerNotice)
        if (!lockTimer.isEnabled) {
            notice.visibility = View.GONE
            return
        }
        notice.visibility = View.VISIBLE
        notice.text = getString(
            R.string.lock_timer_reveal,
            getString(lockTimer.length.label),
        )
    }

    /**
     * On the challenge: the day this lock lifts without anybody typing anything.
     *
     * Two sentences rather than one, because the two timers are not the same
     * news. A period the parent chose before locking is a confirmation; a
     * code-forgotten countdown may have been started by whoever was holding the
     * phone, and a parent who still has their key needs to see that in words
     * strong enough to act on. It is coloured as an error for the same reason.
     *
     * The keyguard carries the same fact — see
     * [app.drawbridge.dpc.admin.DeviceOwnerManager.updateLockScreenInfo] — so
     * noticing it does not depend on anybody opening this app.
     */
    private fun showRunningTimer() {
        val notice = findViewById<TextView>(R.id.timerNotice)
        if (!lockTimer.isArmed) {
            notice.visibility = View.GONE
            return
        }

        val forgotten = lockTimer.reason == LockTimer.Reason.FORGOTTEN
        notice.visibility = View.VISIBLE
        notice.text = getString(
            if (forgotten) R.string.lock_timer_running_forgotten else R.string.lock_timer_running,
            formatMoment(lockTimer.expiresAt),
        )
        // Resolved off the theme rather than from a colour resource, so the line
        // follows dark mode like every other themed colour on these screens.
        notice.setTextColor(
            MaterialColors.getColor(
                notice,
                if (forgotten) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

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
     * The one way the device actually gets sealed: *Done*, with the checkbox
     * ticked. There used to be a second — a deliberate "close without the key"
     * dialog on the way out — and it went with the back-press change described
     * above; this comment still named it until 2026-08-17.
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
        // The countdown starts here for exactly the reason the key is committed
        // here: an abandoned reveal must leave the phone as it was, and a deadline
        // written when this screen opened would have left a phone counting down
        // towards the end of a lock that was never sealed.
        //
        // Read off the draft rather than passed in, so what is armed is whatever
        // the configuration screen last said — including a parent who went back
        // and changed their mind before pressing Lock.
        if (lockTimer.isEnabled) {
            lockTimer.arm(lockTimer.length, LockTimer.Reason.CHOSEN)
        }
        // After the commit, not before: the keyguard line carries the lock date,
        // and until this moment there was no lock and no date. lockDevice()
        // applies the rest of the policy well before the parent has decided to
        // keep the key, so this is the only place that can say the phone is
        // locked and be right.
        val deviceOwner = DeviceOwnerManager(this)
        deviceOwner.updateLockScreenInfo()
        // And the same ordering is why USB debugging is taken away here rather
        // than in lockDevice(). It is the one restriction keyed on the lock
        // itself, so applying it any earlier would take the cable away from a
        // parent who has not yet decided to keep the key — and an abandoned
        // reveal is supposed to leave a phone that can still be worked on.
        deviceOwner.applyUserRestrictions()
        // And app removal is keyed on the lock for the same reason, so this is
        // the moment it can finally run. Before the commit AppBlocker.evaluate
        // declines every package, which includes the full sweep PackageWatcher
        // does when the filter service starts — that happens back in
        // lockDevice(), on a phone that is not locked yet.
        DrawbridgeApplication.sweepOnLock(this)
        // Connectivity too: the chosen philosophy is a draft until this moment,
        // like everything else on the configuration screen, and protectedSince
        // is only non-zero once commit() above has run. This is also what pins
        // the clock when a timer was just armed, since a wall-clock deadline is
        // only as trustworthy as the clock it is measured against.
        CurfewController(this).apply()
        // Sets the alarm for the deadline. Last, because it reads the lock state
        // and the deadline that the lines above have only just written.
        LockTimerController(this).apply()
        finish()
    }

    private fun attemptUnlock() {
        val candidate = keyField.text.toString().trim()
        if (candidate.isEmpty()) return

        if (!parentKey.unlock(candidate)) {
            challengeError.setText(R.string.lock_challenge_incorrect)
            return
        }

        // **The key cancels the timer**, which is what makes a countdown safe to
        // let anybody start. A deadline belongs to one lock: this one is over, so
        // its deadline is too, and a parent who finds their key has nothing to
        // switch off. Before the keyguard line, which would otherwise still name
        // an unlock date for a lock that no longer exists.
        lockTimer.disarm()

        // The phone is still guarded — unlocking removes the key, not the
        // restrictions or the filter — so the line stays and only the date goes.
        val deviceOwner = DeviceOwnerManager(this)
        deviceOwner.updateLockScreenInfo()
        // One restriction does come off: USB debugging, which follows the lock
        // rather than the protection so that a parent holding the key can put a
        // fix on the phone over a cable. See DeviceOwnerManager.restrictionsFor.
        deviceOwner.applyUserRestrictions()

        // And the phone comes back online, whatever the disconnect philosophy
        // says: an unlocked drawbridge is a parent working on the phone, and
        // everything they unlocked to do — install something, move data off,
        // try a browser — needs a network. It goes dark again at the next lock.
        CurfewController(this).apply()
        // And the alarm goes with the deadline. apply() finds nothing armed and
        // cancels the pending unlock, so a lock that ends early leaves no wake-up
        // pointed at a phone that is already open.
        LockTimerController(this).apply()
        // Which makes this the moment to catch up on the blocklists. A phone
        // that has been offline has a stale policy, and the parent is about to
        // use the network it just got back.
        DrawbridgeApplication.fetchPolicyAndRequiredApps(this)

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    /**
     * Diagnostics, the policy check, and the code-forgotten door. Removal is not
     * offered here — it lives behind the key, on the screen this one guards.
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

        R.id.actionForgotKey -> {
            offerForgottenKeyTimer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * The way out of a lock whose key is gone: a thirty-day wait, and no way to
     * shorten it.
     *
     * **This is a deliberate hole of a known size, and the size is the security
     * mechanism.** Anyone holding the phone can start it, the child included —
     * there is no way to tell a parent who lost a piece of paper from a teenager
     * who says they did, and a door that only opens for the honest is not a door.
     * What makes it survivable is that it is slow and loud: a month is not a
     * bypass of a lock somebody set for an evening, the keyguard says the date for
     * every one of those days, and unlocking with the key cancels it — so a parent
     * who still has theirs can end it in seconds and lock again.
     *
     * It exists because the alternative outcome is worse than the hole. Without
     * it, a mislaid key means a phone that can only be reclaimed by wiping
     * everything on it, and the project's own reason for not preventing factory
     * reset is that nobody should pay that for losing a piece of paper.
     *
     * Thirty days rather than the picker's forty: see [LockTimer.FORGOTTEN].
     *
     * A timer already running is not restarted — that would let repeated taps
     * push the date around, and there is nothing to gain from starting a second
     * one — so the dialog simply reports the deadline the phone already has.
     */
    private fun offerForgottenKeyTimer() {
        if (lockTimer.isArmed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.forgot_key_title)
                .setMessage(
                    getString(R.string.forgot_key_running, formatMoment(lockTimer.expiresAt)),
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.forgot_key_title)
            .setMessage(
                getString(R.string.forgot_key_message, getString(LockTimer.FORGOTTEN.label)),
            )
            .setPositiveButton(R.string.forgot_key_start) { _, _ -> startForgottenKeyTimer() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startForgottenKeyTimer() {
        lockTimer.arm(LockTimer.FORGOTTEN, LockTimer.Reason.FORGOTTEN)
        // The keyguard first, because that is the surface a parent who did not
        // start this will be reading, and the clock pin with it: from here on the
        // date is the only thing standing between this phone and an unlock, so
        // Settings may no longer move it.
        DeviceOwnerManager(this).updateLockScreenInfo()
        CurfewController(this).apply()
        LockTimerController(this).apply()

        showRunningTimer()
        AlertDialog.Builder(this)
            .setTitle(R.string.forgot_key_title)
            .setMessage(getString(R.string.forgot_key_started, formatMoment(lockTimer.expiresAt)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            val message = when (val outcome = policy.refresh(userInitiated = true)) {
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
