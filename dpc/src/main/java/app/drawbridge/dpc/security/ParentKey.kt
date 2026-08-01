package app.drawbridge.dpc.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The one secret in drawbridge: a key generated when the parent locks the
 * device, and the only thing that unlocks it again.
 *
 * There used to be a PIN as well, with the key demoted to a recovery code behind
 * it. That was one secret too many. A PIN a parent can remember is six digits —
 * ten thousand guesses, which is an afternoon of tapping — so it needed lockout
 * throttling, and it still needed the key underneath it for when it was
 * forgotten. Everything the PIN did, the key does better, so the PIN is gone and
 * with it the throttling: a hundred bits of entropy does not need protecting
 * from a child guessing, and a lockout on the *only* way in is a way to strand
 * the parent for half an hour with no alternative.
 *
 * A fresh key is minted every time the device is locked, so a key photographed
 * once stops working at the next lock. It is shown exactly once, at that moment;
 * only its salted hash is kept. There is deliberately no reset — an email or
 * account recovery path would reintroduce exactly the account dependency this
 * project exists to avoid — so a parent who does not write the key down cannot
 * unlock the device again, ever. That is a choice they are allowed to make, and
 * the screen that mints the key says so before it does.
 */
class ParentKey(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True while the configuration screen is sealed.
     *
     * Backed by the presence of a key rather than a flag of its own: a locked
     * device with no key would be one no one could ever open, and a stored key
     * with the lock off would be a secret nothing uses.
     */
    val isLocked: Boolean
        get() = prefs.contains(KEY_HASH)

    /**
     * When this phone was first locked, or 0 if it never has been.
     *
     * This is the number a caregiver checks. It survives reboots, it survives
     * unlocking and re-locking, and the only things that clear it are removing
     * drawbridge from inside the app or wiping the phone — which is exactly what
     * makes it useful: if the date is not the day you set the phone up, it has
     * been reset since, whatever else it looks like.
     *
     * It is deliberately not derived from the install time, which a reinstall
     * over the top would leave alone.
     */
    val protectedSince: Long
        get() = prefs.getLong(KEY_PROTECTED_SINCE, 0)

    /** When the current lock began, or 0 if the phone has never been locked. */
    val lockedSince: Long
        get() = prefs.getLong(KEY_LOCKED_SINCE, 0)

    /**
     * Locks the device and returns the key that opens it, once.
     *
     * The caller must show this to the parent before returning: it is not
     * recoverable from anywhere afterwards.
     */
    fun lock(): String {
        val key = generateKey()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val now = System.currentTimeMillis()

        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, derive(key.normalise(), salt).toHex())
            .putLong(KEY_LOCKED_SINCE, now)
            // Written once and then left alone. Re-stamping it at every lock
            // would make a parent's ordinary Tuesday-evening change look exactly
            // like a factory reset, which would cost the number its only use.
            .apply {
                if (!prefs.contains(KEY_PROTECTED_SINCE)) putLong(KEY_PROTECTED_SINCE, now)
            }
            .apply()

        return key
    }

    /**
     * Unlocks if [candidate] is the key, and forgets the key when it does.
     *
     * Forgetting it is what makes each lock independent: no secret is stored at
     * all while the device is unlocked, and the next lock mints a new one rather
     * than reviving the last. The timestamps stay — they outlive individual
     * locks by design.
     */
    fun unlock(candidate: String): Boolean {
        if (!matches(candidate)) return false
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()
        return true
    }

    /** Drops the key and the history with it. Part of the sanctioned removal flow. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun matches(candidate: String): Boolean {
        val storedHash = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        return MessageDigest.isEqual(derive(candidate.normalise(), salt), storedHash)
    }

    /**
     * PBKDF2 is overkill for a hundred-bit random key — a plain SHA-256 would be
     * just as unguessable — but it costs one derivation per unlock attempt and
     * removes any need to reason about whether the key really carries the
     * entropy it is supposed to.
     */
    private fun derive(secret: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded

    companion object {
        private const val PREFS_NAME = "drawbridge_parent_key"
        private const val KEY_HASH = "key_hash"
        private const val KEY_SALT = "key_salt"
        private const val KEY_PROTECTED_SINCE = "protected_since"
        private const val KEY_LOCKED_SINCE = "locked_since"

        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256

        /**
         * Crockford base32: no I, L, O or U, so a handwritten key cannot be
         * misread as a different valid one.
         */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LENGTH = 20
        private const val GROUP = 5

        /** Twenty base-32 characters — a hundred bits, in four readable groups. */
        fun generateKey(): String {
            val random = SecureRandom()
            val raw = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            return String(raw).chunked(GROUP).joinToString("-")
        }

        /** Case- and separator-insensitive, because this gets typed off paper. */
        private fun String.normalise(): String = uppercase().filter { it in ALPHABET }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }
    }
}
