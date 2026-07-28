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

    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 60_000,
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
