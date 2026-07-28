package app.drawbridge.policy.crypto

import app.drawbridge.policy.model.Policy
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Raised whenever a policy document is rejected. Never contains key material. */
class PolicyVerificationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Verifies signed policy documents against the set of public keys baked into the
 * APK, and enforces version monotonicity so an attacker cannot replay an older,
 * more permissive policy.
 */
class PolicyVerifier(trustedKeys: List<TrustedKey>) {

    private val keysById: Map<String, PublicKey> = trustedKeys.associate { key ->
        key.keyId to decodePublicKey(key.keyId, key.publicKey)
    }

    init {
        require(keysById.isNotEmpty()) { "No trusted policy signing keys configured" }
    }

    /**
     * Parses and verifies [envelopeJson].
     *
     * @param minimumVersion the version already held on device; policies at or
     *   below it are rejected. Pass 0 to accept any version.
     * @throws PolicyVerificationException if the envelope is malformed, signed by
     *   an unknown key, has a bad signature, or would roll the policy back.
     */
    fun verify(envelopeJson: String, minimumVersion: Int = 0): VerifiedPolicy {
        val envelope = try {
            JSON.decodeFromString<SignedEnvelope>(envelopeJson)
        } catch (e: Exception) {
            throw PolicyVerificationException("Policy envelope is not valid JSON", e)
        }

        if (envelope.algorithm != SignedEnvelope.SIGNATURE_ALGORITHM) {
            throw PolicyVerificationException(
                "Unsupported policy signature algorithm '${envelope.algorithm}'",
            )
        }

        val publicKey = keysById[envelope.keyId]
            ?: throw PolicyVerificationException("Policy signed by unknown key '${envelope.keyId}'")

        val payloadBytes = decodeBase64(envelope.payload, "payload")
        val signatureBytes = decodeBase64(envelope.signature, "signature")

        val signatureValid = try {
            Signature.getInstance(SignedEnvelope.JCA_SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payloadBytes)
                verify(signatureBytes)
            }
        } catch (e: GeneralSecurityException) {
            // A malformed DER signature throws rather than returning false.
            throw PolicyVerificationException("Policy signature could not be checked", e)
        }

        if (!signatureValid) {
            throw PolicyVerificationException("Policy signature does not match")
        }

        val policy = try {
            JSON.decodeFromString<Policy>(payloadBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw PolicyVerificationException("Signed payload is not a valid policy document", e)
        }

        if (policy.version <= minimumVersion) {
            throw PolicyVerificationException(
                "Policy version ${policy.version} would roll back the installed version $minimumVersion",
            )
        }

        return VerifiedPolicy(policy = policy, envelopeJson = envelopeJson)
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw PolicyVerificationException("Policy $field is not valid base64", e)
    }

    companion object {
        internal val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun decodePublicKey(keyId: String, base64Spki: String): PublicKey = try {
            val der = Base64.getDecoder().decode(base64Spki)
            KeyFactory.getInstance(SignedEnvelope.JCA_KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(der))
        } catch (e: Exception) {
            throw IllegalArgumentException("Trusted key '$keyId' is not a valid P-256 public key", e)
        }

        /** Parses the `trusted-keys.json` asset shipped in the APK. */
        fun fromTrustedKeySet(json: String): PolicyVerifier {
            val set = JSON.decodeFromString<TrustedKeySet>(json)
            return PolicyVerifier(set.keys)
        }
    }
}

/**
 * A policy that passed signature and rollback checks, paired with the exact
 * envelope bytes it came from so it can be persisted and re-verified on load.
 */
data class VerifiedPolicy(
    val policy: Policy,
    val envelopeJson: String,
)
