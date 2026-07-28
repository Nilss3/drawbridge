package app.drawbridge.policy.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wrapper around a policy document: the payload is carried base64-encoded so the
 * exact bytes that were signed survive transport and re-serialisation. Signing
 * the parsed JSON instead would require a canonicalisation scheme, which is a
 * classic source of signature-bypass bugs.
 */
@Serializable
data class SignedEnvelope(
    /** Identifies which trusted key signed this document, so keys can be rotated. */
    @SerialName("key_id")
    val keyId: String,

    /** Only [SIGNATURE_ALGORITHM] is accepted. */
    val algorithm: String = SIGNATURE_ALGORITHM,

    /** Base64 of the policy JSON's UTF-8 bytes. */
    val payload: String,

    /** Base64 of the signature over the decoded payload bytes. */
    val signature: String,
) {
    companion object {
        /**
         * ECDSA over NIST P-256 with SHA-256, rather than the Ed25519 sketched in
         * the design notes: `Signature.getInstance("Ed25519")` only exists from
         * API 33, and this project targets API 28+. P-256 is available on every
         * supported release through the platform provider, so signature checking
         * needs no third-party crypto library.
         */
        const val SIGNATURE_ALGORITHM = "ecdsa-p256-sha256"

        internal const val JCA_SIGNATURE_ALGORITHM = "SHA256withECDSA"
        internal const val JCA_KEY_ALGORITHM = "EC"
    }
}

/** A public key the app is willing to accept policy from. */
@Serializable
data class TrustedKey(
    @SerialName("key_id")
    val keyId: String,
    /** Base64 of the X.509 SubjectPublicKeyInfo DER encoding. */
    @SerialName("public_key")
    val publicKey: String,
    val comment: String? = null,
)

@Serializable
data class TrustedKeySet(
    val keys: List<TrustedKey>,
)
