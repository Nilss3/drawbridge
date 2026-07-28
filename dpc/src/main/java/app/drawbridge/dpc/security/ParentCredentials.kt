package app.drawbridge.dpc.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * The parent's PIN and one-time recovery code.
 *
 * Both are stored as salted PBKDF2 hashes, never in plaintext, and there is
 * deliberately no online reset: an email or account-based recovery path would
 * reintroduce exactly the account dependency this project is built to avoid.
 * If both the PIN and the printed recovery code are lost, the only way out is a
 * destructive recovery-mode wipe — which is the documented trade-off.
 */
class ParentCredentials(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean
        get() = prefs.contains(KEY_PIN_HASH)

    val hasUnusedRecoveryCode: Boolean
        get() = prefs.contains(KEY_RECOVERY_HASH)

    /**
     * Sets the PIN and returns a freshly generated recovery code.
     *
     * The recovery code is returned exactly once, here — only its hash is kept,
     * so the setup screen must show it to the parent to write down or print.
     */
    fun configure(pin: String): String {
        require(pin.length >= MIN_PIN_LENGTH) { "PIN must be at least $MIN_PIN_LENGTH digits" }

        storeHash(KEY_PIN_HASH, KEY_PIN_SALT, pin)

        val recoveryCode = generateRecoveryCode()
        storeHash(KEY_RECOVERY_HASH, KEY_RECOVERY_SALT, recoveryCode.normaliseCode())

        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, 0).apply()
        return recoveryCode
    }

    /** Changes the PIN, leaving the existing recovery code valid. */
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (verify(currentPin) != VerifyResult.Correct) return false
        require(newPin.length >= MIN_PIN_LENGTH) { "PIN must be at least $MIN_PIN_LENGTH digits" }
        storeHash(KEY_PIN_HASH, KEY_PIN_SALT, newPin)
        return true
    }

    /**
     * Checks the PIN, applying a lockout that grows with consecutive failures.
     *
     * A four-digit PIN has ten thousand possibilities; without throttling,
     * exhausting them is a few minutes of tapping.
     */
    fun verify(pin: String): VerifyResult {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0)
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return VerifyResult.LockedOut(lockedUntil - now)

        val correct = matches(KEY_PIN_HASH, KEY_PIN_SALT, pin)
        return if (correct) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, 0).apply()
            VerifyResult.Correct
        } else {
            val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
            val delay = lockoutMillisFor(attempts)
            if (delay > 0) editor.putLong(KEY_LOCKED_UNTIL, now + delay)
            editor.apply()
            VerifyResult.Incorrect(attempts, delay)
        }
    }

    /**
     * Checks the printed recovery code and consumes it — a code that has been
     * used once cannot be replayed, so a photo of it left lying around stops
     * being a key after the first use.
     */
    fun consumeRecoveryCode(code: String): Boolean {
        if (!matches(KEY_RECOVERY_HASH, KEY_RECOVERY_SALT, code.normaliseCode())) return false
        prefs.edit()
            .remove(KEY_RECOVERY_HASH)
            .remove(KEY_RECOVERY_SALT)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0)
            .apply()
        return true
    }

    /** Wipes stored credentials. Part of the sanctioned removal flow. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun storeHash(hashKey: String, saltKey: String, secret: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(saltKey, salt.toHex())
            .putString(hashKey, derive(secret, salt).toHex())
            .apply()
    }

    private fun matches(hashKey: String, saltKey: String, secret: String): Boolean {
        val storedHash = prefs.getString(hashKey, null)?.fromHex() ?: return false
        val salt = prefs.getString(saltKey, null)?.fromHex() ?: return false
        return MessageDigest.isEqual(derive(secret, salt), storedHash)
    }

    private fun derive(secret: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded

    sealed interface VerifyResult {
        data object Correct : VerifyResult

        /** [retryDelayMillis] is 0 while the parent still has free attempts left. */
        data class Incorrect(val consecutiveFailures: Int, val retryDelayMillis: Long) : VerifyResult

        data class LockedOut(val remainingMillis: Long) : VerifyResult
    }

    companion object {
        const val MIN_PIN_LENGTH = 6

        private const val PREFS_NAME = "drawbridge_parent_credentials"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_RECOVERY_HASH = "recovery_hash"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKED_UNTIL = "locked_until"

        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256

        private const val FREE_ATTEMPTS = 4
        private const val BASE_LOCKOUT_MILLIS = 30_000L
        private const val MAX_LOCKOUT_MILLIS = 30 * 60 * 1000L

        /**
         * Crockford base32: no I, L, O or U, so a handwritten code cannot be
         * misread as a different valid one.
         */
        private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 20
        private const val CODE_GROUP = 5

        internal fun lockoutMillisFor(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= FREE_ATTEMPTS) return 0
            val doublings = consecutiveFailures - FREE_ATTEMPTS - 1
            if (doublings >= 31) return MAX_LOCKOUT_MILLIS
            return min(BASE_LOCKOUT_MILLIS shl doublings, MAX_LOCKOUT_MILLIS)
        }

        fun generateRecoveryCode(): String {
            val random = SecureRandom()
            val raw = CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
            return String(raw).chunked(CODE_GROUP).joinToString("-")
        }

        /** Case- and separator-insensitive, because this gets typed off paper. */
        private fun String.normaliseCode(): String =
            uppercase().filter { it in CODE_ALPHABET }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }
    }
}
