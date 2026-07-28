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
 * test exercises credential storage without dragging in policy loading and the
 * device-admin plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ParentCredentialsTest {

    private lateinit var credentials: ParentCredentials

    @Before
    fun setUp() {
        credentials = ParentCredentials(ApplicationProvider.getApplicationContext())
        credentials.clear()
    }

    @Test
    fun `accepts the configured PIN and rejects others`() {
        credentials.configure("135790")

        assertEquals(ParentCredentials.VerifyResult.Correct, credentials.verify("135790"))
        assertTrue(credentials.verify("000000") is ParentCredentials.VerifyResult.Incorrect)
    }

    @Test
    fun `is not configured until a PIN is set`() {
        assertFalse(credentials.isConfigured)
        credentials.configure("135790")
        assertTrue(credentials.isConfigured)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a PIN shorter than the minimum`() {
        credentials.configure("1234")
    }

    @Test
    fun `never stores the PIN in plaintext`() {
        credentials.configure("135790")
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("drawbridge_parent_credentials", 0)

        prefs.all.values.forEach { value ->
            assertFalse("PIN found in stored value $value", value.toString().contains("135790"))
        }
    }

    @Test
    fun `uses a fresh salt for every PIN, so identical PINs hash differently`() {
        credentials.configure("135790")
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("drawbridge_parent_credentials", 0)
        val firstHash = prefs.getString("pin_hash", null)

        credentials.clear()
        credentials.configure("135790")
        val secondHash = prefs.getString("pin_hash", null)

        assertNotEquals(firstHash, secondHash)
    }

    @Test
    fun `accepts the recovery code and consumes it`() {
        val code = credentials.configure("135790")

        assertTrue(credentials.hasUnusedRecoveryCode)
        assertTrue(credentials.consumeRecoveryCode(code))
        // Single use: a photo of the printed code stops being a key after the
        // first successful use.
        assertFalse(credentials.consumeRecoveryCode(code))
        assertFalse(credentials.hasUnusedRecoveryCode)
    }

    @Test
    fun `accepts a recovery code typed without dashes or in lower case`() {
        val code = credentials.configure("135790")
        assertTrue(credentials.consumeRecoveryCode(code.replace("-", "").lowercase()))
    }

    @Test
    fun `rejects a wrong recovery code without consuming the real one`() {
        credentials.configure("135790")
        assertFalse(credentials.consumeRecoveryCode("AAAAA-BBBBB-CCCCC-DDDDD"))
        assertTrue(credentials.hasUnusedRecoveryCode)
    }

    @Test
    fun `changes the PIN only with the current one`() {
        credentials.configure("135790")

        assertFalse(credentials.changePin("999999", "246800"))
        assertTrue(credentials.changePin("135790", "246800"))
        assertEquals(ParentCredentials.VerifyResult.Correct, credentials.verify("246800"))
    }

    @Test
    fun `locks out after repeated wrong PINs`() {
        credentials.configure("135790")

        // The first few failures are free, so a parent fat-fingering the PIN is
        // not punished.
        repeat(4) {
            val result = credentials.verify("000000") as ParentCredentials.VerifyResult.Incorrect
            assertEquals(0, result.retryDelayMillis)
        }

        val throttled = credentials.verify("000000") as ParentCredentials.VerifyResult.Incorrect
        assertTrue(throttled.retryDelayMillis > 0)

        // Even the correct PIN has to wait out the lockout.
        assertTrue(credentials.verify("135790") is ParentCredentials.VerifyResult.LockedOut)
    }

    @Test
    fun `lockout grows but is capped`() {
        val fifth = ParentCredentials.lockoutMillisFor(5)
        val sixth = ParentCredentials.lockoutMillisFor(6)

        assertEquals(0, ParentCredentials.lockoutMillisFor(4))
        assertTrue(fifth > 0)
        assertEquals(fifth * 2, sixth)
        assertEquals(30 * 60 * 1000L, ParentCredentials.lockoutMillisFor(100))
    }

    @Test
    fun `generates distinct recovery codes with no ambiguous characters`() {
        val codes = List(50) { ParentCredentials.generateRecoveryCode() }

        assertEquals(50, codes.toSet().size)
        codes.forEach { code ->
            assertEquals("ABCDE-FGHIJ-KLMNO-PQRST".length, code.length)
            // I, L, O and U are excluded so a handwritten code cannot be misread.
            assertFalse(code.any { it in "ILOU" })
        }
    }
}
