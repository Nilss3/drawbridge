package app.drawbridge.policy

/**
 * Where an app gets its policy from. Both apps point at the same URL and the
 * same signing keys; they differ only in which parts of the resulting policy
 * they act on.
 */
data class PolicyConfig(
    /** HTTPS URL of the signed policy envelope. */
    val policyUrl: String = DEFAULT_POLICY_URL,

    /** Asset path of the trusted signing keys baked into the APK. */
    val trustedKeysAsset: String = "drawbridge/trusted-keys.json",

    /** Asset path of the signed policy shipped in the APK, applied on first run. */
    val bundledPolicyAsset: String = "drawbridge/default-policy.json",

    /** Asset path of the seed blocklist, so a fresh install filters before its first poll. */
    val bundledBlocklistAsset: String? = "drawbridge/default-blocklist.txt",

    /** Refuse downloads larger than this, so a hostile URL cannot fill the disk. */
    val maxDownloadBytes: Long = 64L * 1024 * 1024,

    /**
     * This app's own `versionCode`, for [app.drawbridge.policy.model.Policy.conditional].
     *
     * Only meaningful in the app that *owns* the selection — drawbridge. An app
     * reading somebody else's selection takes the owner's build from
     * [SelectionSource.Selection.ownerVersionCode] instead, because a fragment
     * gated on drawbridge 53 means nothing measured against herald's numbering.
     *
     * Zero, the default, means no conditional fragment ever applies. That is the
     * right resting state for a standalone browser and for any caller that has
     * not thought about it: a fragment only ever *adds* a block, so ignoring one
     * filters less rather than more, and less is what a device with no drawbridge
     * on it is entitled to.
     */
    val ownVersionCode: Int = 0,

    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 60_000,

    /**
     * Where the chosen profile and options come from, when they come from
     * somewhere other than this app.
     *
     * herald sets this to drawbridge's selection, so that a browser on a managed
     * device enforces what the parent actually chose rather than the document's
     * defaults — turning "Allow WhatsApp" on has to unblock WhatsApp Web, and
     * the browser has no other way to know it was turned on. Null, which is what
     * the standalone browser and drawbridge itself use, means "read the
     * selection this app stores for itself".
     */
    val selectionSource: SelectionSource? = null,

    /**
     * What the options read as when [selectionSource] gives no answer: every
     * option off, rather than the document's `default_enabled`.
     *
     * herald sets it. The standalone browser is installed as the only browser on
     * a phone whose apps are already blocked by something else, and with no
     * drawbridge to say which switches are on, the defaults — all four on in
     * policy 133 — would make it the bypass: YouTube, the streaming services and
     * Telegram's web client, in the one browser the phone allows. Every option
     * only ever *releases* something, so "all off" is the strict reading, and
     * the failure mode of a drawbridge that is missing, crashed or mid-upgrade is
     * a browser that blocks more than it needs to rather than less.
     *
     * An answer that arrives without option ids — drawbridge installed, nobody
     * has chosen yet — still follows the defaults, because that is what
     * drawbridge itself enforces at the DNS layer underneath; this only governs
     * the case where nothing answers at all. Ignored when [selectionSource] is
     * null, since an app that owns its selection has its own stored answer.
     */
    val optionsOffWithoutSelection: Boolean = false,
) {
    companion object {
        /**
         * Overridden per-app at build time. Kept as a constant rather than a
         * BuildConfig field so the library has no dependency on either app's
         * build setup.
         */
        const val DEFAULT_POLICY_URL =
            "https://raw.githubusercontent.com/Nilss3/drawbridge/main/dist/policy.signed.json"
    }
}
