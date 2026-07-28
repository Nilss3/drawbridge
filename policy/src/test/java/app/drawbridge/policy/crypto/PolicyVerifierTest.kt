package app.drawbridge.policy.crypto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PolicyVerifierTest {

    private lateinit var keyPair: KeyPair
    private lateinit var verifier: PolicyVerifier

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        verifier = PolicyVerifier(
            listOf(
                TrustedKey(
                    keyId = KEY_ID,
                    publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                ),
            ),
        )
    }

    @Test
    fun `accepts a correctly signed policy`() {
        val verified = verifier.verify(envelope(policyJson(version = 4)))
        assertEquals(4, verified.policy.version)
    }

    @Test
    fun `reads the fields of the signed document`() {
        val json = """
            {
              "version": 2,
              "blocked_packages": ["com.instagram.android"],
              "allowed_browser_package": "app.drawbridge.herald",
              "dns": { "upstreams": ["1.1.1.3"], "enforce_safe_search": false }
            }
        """.trimIndent()
        val policy = verifier.verify(envelope(json)).policy

        assertEquals(listOf("com.instagram.android"), policy.blockedPackages)
        assertEquals("app.drawbridge.herald", policy.allowedBrowserPackage)
        assertEquals(listOf("1.1.1.3"), policy.dns.upstreams)
        assertEquals(false, policy.dns.enforceSafeSearch)
    }

    @Test
    fun `ignores unknown fields so newer policies stay readable`() {
        val json = """{ "version": 1, "some_future_field": { "nested": true } }"""
        assertEquals(1, verifier.verify(envelope(json)).policy.version)
    }

    @Test
    fun `rejects a tampered payload`() {
        val original = envelope(policyJson(version = 2))
        val tampered = Json.parseToJsonElement(original).toString().replace(
            Base64.getEncoder().encodeToString(policyJson(version = 2).toByteArray()),
            Base64.getEncoder().encodeToString(policyJson(version = 99).toByteArray()),
        )

        val error = assertThrows(PolicyVerificationException::class.java) {
            verifier.verify(tampered)
        }
        assertEquals("Policy signature does not match", error.message)
    }

    @Test
    fun `rejects a policy signed by an unknown key`() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val json = policyJson(version = 3)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(other.private)
            update(json.toByteArray())
            sign()
        }
        val envelope = """
            {"key_id":"someone-else","algorithm":"ecdsa-p256-sha256",
             "payload":"${json.toByteArray().base64()}","signature":"${signature.base64()}"}
        """.trimIndent()

        assertThrows(PolicyVerificationException::class.java) { verifier.verify(envelope) }
    }

    @Test
    fun `rejects a rollback to an older version`() {
        val error = assertThrows(PolicyVerificationException::class.java) {
            verifier.verify(envelope(policyJson(version = 3)), minimumVersion = 7)
        }
        assertEquals(
            "Policy version 3 would roll back the installed version 7",
            error.message,
        )
    }

    @Test
    fun `rejects a replay of the currently installed version`() {
        assertThrows(PolicyVerificationException::class.java) {
            verifier.verify(envelope(policyJson(version = 5)), minimumVersion = 5)
        }
    }

    @Test
    fun `rejects an unsupported algorithm`() {
        val json = policyJson(version = 1)
        val envelope = """
            {"key_id":"$KEY_ID","algorithm":"none",
             "payload":"${json.toByteArray().base64()}","signature":"${"x".toByteArray().base64()}"}
        """.trimIndent()

        assertThrows(PolicyVerificationException::class.java) { verifier.verify(envelope) }
    }

    @Test
    fun `rejects a malformed signature without crashing`() {
        val json = policyJson(version = 1)
        val envelope = """
            {"key_id":"$KEY_ID","algorithm":"ecdsa-p256-sha256",
             "payload":"${json.toByteArray().base64()}","signature":"${"not-der".toByteArray().base64()}"}
        """.trimIndent()

        assertThrows(PolicyVerificationException::class.java) { verifier.verify(envelope) }
    }

    private fun policyJson(version: Int) = """{"version":$version}"""

    private fun envelope(payloadJson: String): String {
        val payload = payloadJson.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return """
            {"key_id":"$KEY_ID","algorithm":"ecdsa-p256-sha256",
             "payload":"${payload.base64()}","signature":"${signature.base64()}"}
        """.trimIndent()
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val KEY_ID = "test-key"
    }
}
