package app.drawbridge.dpc.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether this phone keeps the door drawbridge was installed through.
 *
 * **Trial mode is what every phone has always been**, and naming it is most of
 * what this class does. A parent who has the key can open the configuration
 * screen, press *Deactivate drawbridge restrictions*, and uninstall the app —
 * the sanctioned way out, built so that a child growing up or a phone being sold
 * costs nobody their data. That door is the right default for a project whose
 * users are trying it out, and it is the reason the beta could be handed to
 * people at all.
 *
 * It is also, for a household that has stopped trying it out, the whole lock
 * reduced to whoever can reach the menu. **Permanent mode closes it**, and the
 * two rows below are the entire feature:
 *
 * | | unlocked | locked |
 * |---|---|---|
 * | **trial** | deactivate from the menu, then uninstall | factory reset |
 * | **permanent** | factory reset | factory reset, after unlocking |
 *
 * The bottom-right cell is the one that costs something, and it is
 * [android.os.UserManager.DISALLOW_FACTORY_RESET] — see
 * [app.drawbridge.dpc.admin.DeviceOwnerManager.MANAGED_RESTRICTIONS] for what
 * that restriction really does, which is more than its name says. Everything
 * else here is the absence of a menu item.
 *
 * ### One way, on purpose
 *
 * There is no `makeTrial()`. A switch that could be flicked back would leave
 * permanent mode meaning *trial mode plus one tap*, and the top-right cell
 * would be false: an unlocked phone whose parent can return to trial mode can
 * still be deactivated from the menu, just with a step in front of it. The
 * button that sets this says so before it does, in
 * `R.string.permanence_confirm_message`, and it is the only writer.
 *
 * **What that is not is a phone nobody can reclaim.** Permanence never takes
 * the factory reset away from an *unlocked* phone, and the restriction that
 * takes it from a locked one is keyed on [ParentKey.isLocked] — so the parent
 * holding the key unlocks, wipes, and has an ordinary handset back. A key that
 * is gone is answered by the thirty-day door on the lock screen, which is the
 * same answer permanence's own explanation gives. That door is the load-bearing
 * one here: it is what keeps *permanent* from meaning *bricked*, and it is why
 * this feature could not have shipped before the lock timer did.
 *
 * ### Device-local, like every other decision on that screen
 *
 * Not policy. The signed document says what the web may contain and which apps
 * are unsafe; whether this particular household is still evaluating drawbridge
 * is not something a document signed by this project could know. It sits beside
 * [app.drawbridge.dpc.apps.InstallLockSettings] and `BrowserSettings` for the
 * same reason, in its own preference file so that clearing one clears none of
 * the others.
 */
class Permanence(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * **Defaults false**, which is to say every phone starts in trial mode —
     * including one that has been provisioned, locked and run for a year. The
     * default is not a guess about what the household wants; it is the state
     * that can still be changed into the other one.
     */
    val isPermanent: Boolean
        get() = prefs.getBoolean(KEY_PERMANENT, false)

    /**
     * Closes the door, for good.
     *
     * `commit` rather than `apply`: this is the one write in the app whose loss
     * would be silent and unrecoverable in the wrong direction. A parent who
     * pressed the button, read the confirmation and watched the banner change
     * would have a phone that quietly went back to trial mode if the process
     * died before the asynchronous write landed — and nothing anywhere would
     * ever say so, because trial mode looks exactly like a phone that was never
     * made permanent. The restriction it governs is only re-derived at the next
     * lock, so there is no second chance to notice.
     */
    fun makePermanent() {
        prefs.edit().putBoolean(KEY_PERMANENT, true).commit()
    }

    /**
     * Forgets the choice, on the way out of drawbridge entirely.
     *
     * Only [app.drawbridge.dpc.ui.RemoveActivity] calls this, and only after the
     * restrictions are down — so it is not a way back to trial mode, it is the
     * same tidying every other device-local setting gets on the way out.
     *
     * **In every reachable state it is a no-op, and that is the proof rather
     * than an oversight.** Removal is reached from one menu item, permanence
     * hides that item, so the activity that calls this can only run on a phone
     * that was still in trial mode — where the flag is already false. It is
     * here because that argument is about a menu, and a menu is a thing that
     * gets rearranged: if removal ever grows a second entrance, a reinstall on
     * the same handset should still not inherit a decision made in a previous
     * life.
     */
    fun clear() {
        prefs.edit().remove(KEY_PERMANENT).apply()
    }

    private companion object {
        const val PREFS_NAME = "drawbridge_permanence"
        const val KEY_PERMANENT = "permanent"
    }
}
