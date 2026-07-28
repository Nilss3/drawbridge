package app.drawbridge.dpc.ui

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.security.ParentCredentials
import app.drawbridge.policy.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Choosing which policy profile the device runs under.
 *
 * Behind the parent's PIN, because it is the same authority that removes
 * drawbridge: a profile switch decides which apps may exist and which resolver
 * the whole device uses.
 *
 * The confirmation is not ceremony. Applying a profile runs the app blocker
 * immediately, and an app it does not allow is uninstalled — choosing the looser
 * profile again will not bring it back.
 */
class ProfilePicker(
    private val activity: Activity,
    private val owner: LifecycleOwner,
    private val onApplied: () -> Unit,
) {

    private val credentials = ParentCredentials(activity)
    private val policy = DrawbridgeApplication.policy(activity)

    /** Entry point: PIN first, then the list. */
    fun start() {
        if (policy.profiles.isEmpty()) {
            Toast.makeText(activity, R.string.profile_none_available, Toast.LENGTH_LONG).show()
            return
        }
        promptForPin()
    }

    private fun promptForPin() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.profile_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.profile_pin_title)
            .setView(input)
            .setPositiveButton(R.string.profile_pin_continue) { _, _ ->
                verify(input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun verify(secret: String) {
        if (secret.isEmpty()) return

        when (val result = credentials.verify(secret)) {
            is ParentCredentials.VerifyResult.Correct -> chooseProfile()

            is ParentCredentials.VerifyResult.LockedOut -> toast(
                activity.getString(
                    R.string.profile_pin_locked,
                    TimeUnit.MILLISECONDS.toSeconds(result.remainingMillis),
                ),
            )

            is ParentCredentials.VerifyResult.Incorrect -> {
                // The recovery code works here too, for the same reason it works
                // for removal: a forgotten PIN must not strand the parent.
                if (credentials.consumeRecoveryCode(secret)) {
                    chooseProfile()
                } else {
                    toast(activity.getString(R.string.profile_pin_incorrect))
                }
            }
        }
    }

    private fun chooseProfile() {
        val profiles = policy.profiles
        val current = policy.selectedProfile?.id
        val checked = profiles.indexOfFirst { it.id == current }

        AlertDialog.Builder(activity)
            .setTitle(R.string.profile_choose_title)
            .setSingleChoiceItems(profiles.map(::label).toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (profiles[which].id != current) confirm(profiles[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Name on the first line, description under it in smaller type. */
    private fun label(profile: Profile): CharSequence {
        val text = SpannableStringBuilder(profile.name)
        if (profile.description.isNotBlank()) {
            val start = text.length
            text.append("\n").append(profile.description)
            text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun confirm(profile: Profile) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.profile_confirm_title, profile.name))
            .setMessage(R.string.profile_confirm_message)
            .setPositiveButton(R.string.profile_confirm_apply) { _, _ -> apply(profile) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun apply(profile: Profile) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.profile_applying_title)
            .setMessage(R.string.profile_applying_message)
            .setCancelable(false)
            .show()

        owner.lifecycleScope.launch {
            // selectProfile syncs the profile's blocklists before swapping the
            // filter, so the app sweep below runs against the policy that is
            // actually in force rather than the one being replaced.
            val selected = policy.selectProfile(profile.id)
            val removed = if (selected) {
                withContext(Dispatchers.IO) { AppBlocker(activity).sweep() }
            } else {
                emptyMap()
            }

            progress.dismiss()
            if (selected) {
                toast(
                    activity.resources.getQuantityString(
                        R.plurals.profile_applied,
                        removed.size,
                        profile.name,
                        removed.size,
                    ),
                )
                onApplied()
            } else {
                toast(activity.getString(R.string.profile_apply_failed))
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
