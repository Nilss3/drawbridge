package app.drawbridge.policy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a phone when the document grows an option it has never seen.
 *
 * **This is the test the feature exists for.** Every option in this project
 * *releases* something the base policy blocks, so an option that arrives
 * switched off is a tool taken away from somebody who had it — and on a locked
 * phone, taken away until they spend the key. The stored list cannot tell a new
 * option from a refused one, because both are simply absent from it, which is
 * why there is a second list.
 *
 * The three cases that matter are here, and the third is the one that makes the
 * mechanism safe rather than merely convenient: a parent who switched something
 * off must not have it handed back.
 */
class NewOptionDefaultTest {

    private val whatsapp = PolicyOption(
        id = "whatsapp",
        name = "Allow WhatsApp",
        defaultEnabled = true,
        exemptPackages = listOf("com.whatsapp"),
    )

    private val chatbots = PolicyOption(
        id = "chatbots",
        name = "Allow AI chatbots",
        defaultEnabled = true,
        allowedDomains = listOf("chatgpt.com"),
    )

    /** The document as it was, before the new option existed. */
    private val before = Policy(version = 1, options = listOf(whatsapp))

    /** The same document, one option later. */
    private val after = Policy(version = 2, options = listOf(whatsapp, chatbots))

    @Test
    fun `a phone that has never chosen anything gets the defaults`() {
        assertEquals(setOf("whatsapp", "chatbots"), after.enabledOptionIds(stored = null))
    }

    /**
     * The regression this was built to stop. The parent kept WhatsApp on, so the
     * stored list is `[whatsapp]` — and `chatbots` is absent from it purely
     * because it did not exist when that list was written.
     */
    @Test
    fun `an option the phone has never been offered arrives at its default`() {
        val enabled = after.enabledOptionIds(
            stored = listOf("whatsapp"),
            seen = before.offeredOptionIds().toList(),
        )
        assertTrue("chatbots should arrive on, not off", "chatbots" in enabled)
        assertTrue("whatsapp" in enabled)
    }

    /**
     * The other half, and the reason this needs a seen set rather than just
     * re-applying defaults to anything absent: a parent who switched WhatsApp
     * off must not find it back.
     */
    @Test
    fun `an option the parent switched off stays off`() {
        val enabled = after.enabledOptionIds(
            stored = emptyList(),
            seen = after.offeredOptionIds().toList(),
        )
        assertFalse("whatsapp" in enabled)
        assertFalse("chatbots" in enabled)
    }

    @Test
    fun `a new option arrives on while a refused one stays off`() {
        val enabled = after.enabledOptionIds(
            stored = emptyList(),
            seen = before.offeredOptionIds().toList(),
        )
        assertFalse("whatsapp was switched off", "whatsapp" in enabled)
        assertTrue("chatbots was never offered", "chatbots" in enabled)
    }

    /**
     * A device that predates the seen set must behave exactly as it did. The
     * only other reading — absent means new — would switch every refused option
     * back on, which is the failure this class is guarding against, applied to
     * every option at once.
     */
    @Test
    fun `a device with no seen set keeps the old behaviour`() {
        val enabled = after.enabledOptionIds(stored = listOf("whatsapp"), seen = null)
        assertEquals(setOf("whatsapp"), enabled)
    }

    @Test
    fun `an option the document has dropped is still discarded`() {
        val enabled = after.enabledOptionIds(
            stored = listOf("whatsapp", "gone"),
            seen = after.offeredOptionIds().toList(),
        )
        assertEquals(setOf("whatsapp"), enabled)
    }

    @Test
    fun `a new option that is off by default still arrives off`() {
        val quiet = after.copy(
            options = listOf(whatsapp, chatbots.copy(defaultEnabled = false)),
        )
        val enabled = quiet.enabledOptionIds(
            stored = listOf("whatsapp"),
            seen = before.offeredOptionIds().toList(),
        )
        assertFalse("chatbots" in enabled)
    }
}
