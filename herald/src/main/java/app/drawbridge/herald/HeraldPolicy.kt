package app.drawbridge.herald

import android.content.Context
import app.drawbridge.policy.PolicyConfig
import app.drawbridge.policy.PolicyManager

/**
 * herald's view of the shared policy.
 *
 * The browser reads the same signed document as the DPC, but acts on a subset of
 * it: the blocklists and the `browser` section. That is what lets the standalone
 * APK (deliverable 2) stay remotely updatable with no account and no DPC.
 */
object HeraldPolicy {

    val config = PolicyConfig()

    fun manager(context: Context): PolicyManager = PolicyManager.getInstance(context, config)
}
