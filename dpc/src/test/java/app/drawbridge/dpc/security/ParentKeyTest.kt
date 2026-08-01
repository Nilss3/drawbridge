package app.drawbridge.dpc.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uses a plain [android.app.Application] rather than drawbridge's own, so the
 * test exercises key storage without dragging in policy loading and the
 * device-admin plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ParentKeyTest {

    private lateinit var key: ParentKey

    @Before
    fun setUp() {
        key = ParentKey(ApplicationProvider.getApplicationContext())
        key.clear()
    }

    @Test
    fun `starts unlocked and locks when a key is minted`() {
        assertFalse(key.isLocked)
        key.lock()
        assertTrue(key.isLocked)
    }

    @Test
    fun `accepts the minted key and rejects anything else`() {
        val minted = key.lock()

        assertFalse(key.unlock("AAAAA-BBBBB-CCCCC-DDDDD"))
        assertTrue(key.isLocked)
        assertTrue(key.unlock(minted))
        assertFalse(key.isLocked)
    }

    @Test
    fun `accepts a key typed without dashes or in lower case`() {
        val minted = key.lock()
        assertTrue(key.unlock(minted.replace("-", "").lowercase()))
    }

    @Test
    fun `a used key does not open the next lock`() {
        val first = key.lock()
        key.unlock(first)
        val second = key.lock()

        assertNotEquals(first, second)
        // Which is the point of minting a fresh one each time: a key
        // photographed once stops working at the next lock.
        assertFalse(key.unlock(first))
        assertTrue(key.unlock(second))
    }

    @Test
    fun `never stores the key in plaintext`() {
        val minted = key.lock()
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("drawbridge_parent_key", 0)

        prefs.all.values.forEach { value ->
            assertFalse("key found in stored value $value", value.toString().contains(minted))
        }
    }

    @Test
    fun `no secret is stored while unlocked`() {
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("drawbridge_parent_key", 0)

        val minted = key.lock()
        assertTrue(prefs.contains("key_hash"))

        assertFalse(key.unlock("AAAAA-BBBBB-CCCCC-DDDDD"))
        assertTrue("a wrong key must not clear the real one", prefs.contains("key_hash"))

        key.unlock(minted)
        assertFalse(prefs.contains("key_hash"))
        assertFalse(prefs.contains("key_salt"))
    }

    @Test
    fun `the protected-since date outlives individual locks`() {
        val first = key.lock()
        val protectedSince = key.protectedSince
        assertTrue(protectedSince > 0)

        key.unlock(first)
        // Still set while unlocked: it is a record of this phone, not of a lock.
        assertEquals(protectedSince, key.protectedSince)

        key.lock()
        // Not re-stamped, or a parent changing a setting would look exactly like
        // a factory reset — which is the one thing this number exists to reveal.
        assertEquals(protectedSince, key.protectedSince)
    }

    @Test
    fun `locked-since moves with each lock`() {
        val first = key.lock()
        val firstLock = key.lockedSince
        assertTrue(firstLock > 0)

        key.unlock(first)
        key.lock()

        assertTrue(key.lockedSince >= firstLock)
    }

    @Test
    fun `removal clears the history as well as the key`() {
        key.lock()
        key.clear()

        assertEquals(0, key.protectedSince)
        assertEquals(0, key.lockedSince)
        assertFalse(key.isLocked)
    }

    @Test
    fun `generates distinct keys with no ambiguous characters`() {
        val keys = List(50) { ParentKey.generateKey() }

        assertEquals(50, keys.toSet().size)
        keys.forEach {
            assertEquals("ABCDE-FGHIJ-KLMNO-PQRST".length, it.length)
            // I, L, O and U are excluded so a handwritten key cannot be misread.
            assertFalse(it.any { character -> character in "ILOU" })
        }
    }
}
