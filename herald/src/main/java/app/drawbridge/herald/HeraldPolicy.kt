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
 *
 * It also reads the *selection* from drawbridge when drawbridge is installed —
 * see [DrawbridgeSelection]. The document alone does not say whether the parent
 * switched an option on, and a browser that ignored that would enforce different
 * rules from the DNS layer sitting underneath it.
 */
object HeraldPolicy {

    /**
     * Built once per process rather than held as a constant, because the
     * selection source needs a Context.
     *
     * Cached deliberately: [PolicyManager.getInstance] takes the config of
     * whichever call creates the singleton and ignores every later one, so a
     * config rebuilt per call would work by luck and break the day something
     * asked for the manager before this did.
     */
    @Volatile
    private var cachedConfig: PolicyConfig? = null

    fun config(context: Context): PolicyConfig = cachedConfig ?: synchronized(this) {
        cachedConfig ?: PolicyConfig(
            selectionSource = DrawbridgeSelection(context),
        ).also { cachedConfig = it }
    }

    fun manager(context: Context): PolicyManager =
        PolicyManager.getInstance(context, config(context))
}
