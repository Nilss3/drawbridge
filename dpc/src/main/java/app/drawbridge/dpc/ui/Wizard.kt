package app.drawbridge.dpc.ui

import android.content.Context
import androidx.annotation.StringRes
import app.drawbridge.dpc.R
import app.drawbridge.dpc.apps.BrowserSettings
import app.drawbridge.dpc.curfew.DisconnectSettings

/**
 * The age the setup wizard asks for, as the youngest year of the band.
 *
 * [years] is compared against the ages already printed on the options, the
 * browser choices and the offline modes — "recommended from 14" — so choosing a
 * band presets each control to what its own shield already says is reasonable
 * at that age. Under-14 is 13 for that comparison: it is the band *below* 14.
 */
enum class WizardAge(val years: Int, @param:StringRes val label: Int) {
    UNDER_14(13, R.string.wizard_age_under_14),
    AGE_14_16(14, R.string.wizard_age_14_16),
    AGE_16_18(16, R.string.wizard_age_16_18),
    ADULT(18, R.string.wizard_age_adult),
    ;

    companion object {
        fun from(name: String?): WizardAge? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What each age band presets. Pure, so the table can be tested and read in one
 * place rather than inferred from the activity.
 *
 * The presets are a starting point the parent adjusts on the following pages,
 * and none of them is enforced until the phone is locked, like every other
 * control on the configuration screen.
 */
object WizardPresets {

    /**
     * Whether an option the policy offers is allowed at [age]. An option with no
     * recommended age keeps whatever it is set to now.
     */
    fun optionEnabled(age: WizardAge, recommendedAge: Int?, current: Boolean): Boolean =
        if (recommendedAge == null) current else age.years >= recommendedAge

    /** No browser under 14, herald alone from 14, every sanctioned one from 16. */
    fun browsers(age: WizardAge): BrowserSettings.Choice = when (age) {
        WizardAge.UNDER_14 -> BrowserSettings.Choice.NONE
        WizardAge.AGE_14_16 -> BrowserSettings.Choice.MONO_ONLY
        WizardAge.AGE_16_18, WizardAge.ADULT -> BrowserSettings.Choice.ALL
    }

    /** No further installs under 14 only. */
    fun installLock(age: WizardAge): Boolean = age == WizardAge.UNDER_14

    /** Offline under 14, an evening curfew from 14, online from 16. */
    fun disconnect(age: WizardAge): DisconnectSettings.Mode = when (age) {
        WizardAge.UNDER_14 -> DisconnectSettings.Mode.OFFLINE
        WizardAge.AGE_14_16 -> DisconnectSettings.Mode.CURFEW
        WizardAge.AGE_16_18, WizardAge.ADULT -> DisconnectSettings.Mode.ONLINE
    }

    /**
     * Whether the settings chosen so far do anything before the lock. Everything
     * the wizard sets lands at the lock, so with nothing restricted there is
     * nothing to lock for and the last page is skipped.
     */
    fun requiresLocking(
        allOptionsEnabled: Boolean,
        browsers: BrowserSettings.Choice,
        installLock: Boolean,
        disconnect: DisconnectSettings.Mode,
    ): Boolean = !allOptionsEnabled ||
        browsers != BrowserSettings.Choice.ALL ||
        installLock ||
        disconnect != DisconnectSettings.Mode.ONLINE
}

/** Whether the wizard has been shown, and the age that was picked in it. */
class WizardSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once the wizard has opened, so it greets a newcomer once and not on every launch. */
    var shown: Boolean
        get() = prefs.getBoolean(KEY_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOWN, value).apply()

    var age: WizardAge?
        get() = WizardAge.from(prefs.getString(KEY_AGE, null))
        set(value) = prefs.edit().putString(KEY_AGE, value?.name).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS_NAME = "drawbridge_wizard"
        const val KEY_SHOWN = "shown"
        const val KEY_AGE = "age"
    }
}
