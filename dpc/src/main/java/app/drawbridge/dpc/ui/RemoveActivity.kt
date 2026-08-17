package app.drawbridge.dpc.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.drawbridge.dpc.DrawbridgeApplication
import app.drawbridge.dpc.R
import app.drawbridge.dpc.admin.DeviceOwnerManager
import app.drawbridge.dpc.apps.AppBlocker
import app.drawbridge.dpc.apps.InstallLockSettings
import app.drawbridge.dpc.apps.store.StoreCatalogue
import app.drawbridge.dpc.security.ParentKey
import app.drawbridge.dpc.vpn.DnsFilterService

/**
 * The sanctioned way out. This lifts every restriction and gives up Device
 * Owner without wiping the device — the child grows up, or the phone gets sold,
 * and nothing is lost.
 *
 * It no longer asks for a secret. It used to, back when the configuration screen
 * was open to anyone and this was the one door with a lock on it. Now the whole
 * screen this is reached from is behind the key, so asking again would be asking
 * the same question twice — and the answer to "is this person allowed to remove
 * parental controls" was already given at the lock screen.
 */
class RemoveActivity : AppCompatActivity() {

    private val parentKey by lazy { ParentKey(this) }
    private val deviceOwner by lazy { DeviceOwnerManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remove)

        findViewById<View>(R.id.root).applyScreenInsets()

        findViewById<Button>(R.id.removeButton).setOnClickListener { confirmRemoval() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
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
        val wasOwner = deviceOwner.isDeviceOwner
        // Restore hidden system browsers *before* giving up ownership: afterwards
        // there is no privilege left to un-hide them, and the device would be
        // stuck with Chrome permanently invisible.
        AppBlocker(this).unhideAll()
        val released = deviceOwner.releaseDeviceOwnership()

        // Only now, because releaseDeviceOwnership is what drops the always-on
        // VPN. Stopping the filter while always-on is still set does nothing
        // lasting: Android restarts the service immediately, by design. Doing it
        // first — as this did — left the tunnel running, and once ownership is
        // gone nothing can clear the setting or stop the service through policy
        // again. Verified on a Moto G15 on 2026-08-07: after a "remove", the
        // filter was still foreground and still resolving blocked names to
        // nothing, on a device that no longer had a device owner.
        DnsFilterService.requestStop(this)

        DrawbridgeApplication.policy(this).clear()
        parentKey.clear()
        // The restriction itself came off with clearUserRestrictions above; this
        // is the closed set behind it. A list of the packages a phone happened to
        // carry means nothing once nothing enforces it, and leaving it would have
        // a reinstalled drawbridge measure the device against a set from before
        // it was removed.
        InstallLockSettings(this).clear()
        // Derived data about apps on a phone drawbridge no longer manages. It
        // enforces nothing once the rule is gone, and a reinstalled drawbridge
        // should ask the store afresh rather than believe a cache from before it
        // was removed.
        StoreCatalogue(this).clear()

        val message = when {
            !wasOwner -> getString(R.string.remove_done_not_owner)
            released -> getString(R.string.remove_done)
            else -> getString(R.string.remove_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
