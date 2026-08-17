package app.drawbridge.dpc.security

import android.content.Context
import android.content.SharedPreferences
import app.drawbridge.dpc.BuildConfig
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
 * project exists to avoid.
 *
 * **What answers a lost key is a clock, not a recovery code.** [LockTimer] can
 * end a lock after a period chosen before it was sealed, and the code-forgotten
 * door on the lock screen can start a thirty-day one afterwards; both end at
 * [forget]. A parent may still choose to keep no copy of the key and set no
 * timer, and then the settings are sealed for good — that is a choice they are
 * allowed to make, and the screen that mints the key says so before it does.
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
     * Seals the device with a key that has already been shown to the parent.
     *
     * Deliberately separate from minting it. The key used to be generated and
     * stored in the same breath, on the way *into* the reveal screen, so that the
     * screen could never show a key different from the stored one — and the cost
     * of that was a phone which locked itself the moment the screen appeared.
     * Pressing home before writing the key down left a sealed device whose key
     * had existed only on a screen nobody had read, and there is no way back from
     * that short of a factory reset. Observed on the reference device on
     * 2026-08-09.
     *
     * So nothing is written until the parent says they have the key. The key is
     * generated in memory, shown, and only committed here — which means an
     * abandoned reveal leaves the phone exactly as it was, and the identity
     * between what was shown and what is stored is kept by passing the same
     * string rather than by writing it early.
     */
    fun commit(key: String) {
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
        forget()
        return true
    }

    /**
     * Ends the lock without being given the key. **The timer's door, and only
     * the timer's.**
     *
     * It does exactly what a successful [unlock] does to the stored state — the
     * key and its salt go, the timestamps stay — so the two unlocks cannot drift
     * apart. What makes it safe is not this method but what can reach it: nothing
     * but [LockTimerController.apply], which acts on a deadline that was written
     * while the phone was already locked and that a moved clock cannot bring
     * forward. There is no caller that takes a secret and no caller reachable from
     * a screen.
     *
     * It is a separate method rather than an `unlock(null)` because a nullable
     * candidate is the kind of parameter that eventually gets passed a value
     * somebody did not check.
     */
    fun forget() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()
    }

    /** Drops the key and the history with it. Part of the sanctioned removal flow. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun matches(candidate: String): Boolean {
        val normalised = candidate.normalise()
        if (matchesEmergencyKey(normalised)) return true

        val storedHash = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        return MessageDigest.isEqual(derive(normalised, salt), storedHash)
    }

    /**
     * A second key that opens any device this build is installed on.
     *
     * **This is a back door, and it is here on purpose while the project is
     * still being built on real hardware.** A phone that locks with a key nobody
     * wrote down is otherwise only recoverable by a factory reset, which on the
     * reference device costs a re-provision and every test result on it.
     *
     * What limits the damage:
     *
     *  - The APK carries only the hash, so pulling the build apart does not
     *    yield the key. It is generated with [generateKey], so it has the same
     *    hundred bits as any other.
     *  - It exists only when `emergencyKey` is set in the untracked
     *    `keystore.properties` (or `DRAWBRIDGE_EMERGENCY_KEY`). A build without
     *    it compiles the check away to a constant-empty string, so a clean build
     *    has no second key at all.
     *  - Diagnostics says so on screen when a build has one, because a back door
     *    nobody can see is worse than one everybody can.
     *
     * What does not limit the damage: it is the same key on every device, it
     * never rotates, and it survives every lock. **Ship no build with it to
     * anyone else, and remove it before the first real deployment.** The
     * sanctioned answer to a lost key is the delayed self-removal on the
     * roadmap, not this.
     *
     * SHA-256 rather than PBKDF2 because this input is high-entropy by
     * construction — the same reasoning the comment on [derive] already makes —
     * and because the hash has to be computable from the build script.
     */
    private fun matchesEmergencyKey(normalised: String): Boolean {
        val expected = BuildConfig.EMERGENCY_KEY_SHA256.fromHex()
        if (expected == null || expected.isEmpty()) return false
        val offered = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray())
        return MessageDigest.isEqual(offered, expected)
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

        /**
         * Case- and separator-insensitive, because this gets typed off paper —
         * and forgiving about the characters the alphabet leaves out, which is
         * the point of choosing Crockford's in the first place.
         *
         * The alphabet has no O, I or L, so a key never contains one. What it
         * does contain is 0 and 1, and a parent reading their own handwriting
         * cannot tell those from O and l. Filtering unknown characters out — the
         * old behaviour — turned that into a key one character short, rejected
         * with "that is not the key" and no clue why. Reported from the phone on
         * 2026-08-09 after exactly that.
         *
         * Crockford specifies the mapping, so this is the decoder being correct
         * rather than a lenience of our own: O reads as 0, I and L read as 1. U
         * has no digit to be confused with — it is excluded so that no key can
         * spell anything unfortunate — so it stays dropped.
         */
        private fun String.normalise(): String = uppercase()
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }
            .filter { it in ALPHABET }
            .joinToString("")

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }
    }
}
