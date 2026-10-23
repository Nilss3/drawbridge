package app.drawbridge.policy

import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequence a phone goes through when the document grows a default-on option,
 * end to end: *apply* the document (which records what was offered) and then
 * *read the selection back*, as the next screen, restart or refresh does.
 * [app.drawbridge.policy.model.NewOptionDefaultTest] pins each half of the rule
 * separately; the bug lived in the step between them.
 */
class OfferedOptionsTest {

    private val whatsapp = PolicyOption(id = "whatsapp", name = "Allow WhatsApp", defaultEnabled = true)
    private val chatbots = PolicyOption(id = "chatbots", name = "Allow chatbots", defaultEnabled = true)
    private val optIn = PolicyOption(id = "optin", name = "Opt in", defaultEnabled = false)

    private val before = Policy(version = 1, options = listOf(whatsapp))
    private val after = Policy(version = 2, options = listOf(whatsapp, chatbots, optIn))

    /** A parent who once touched a switch: a stored list, and a seen set from `before`. */
    private val existing = PolicyStore.StoredState(
        optionIds = listOf("whatsapp"),
        seenOptionIds = listOf("whatsapp"),
    )

    private fun read(state: PolicyStore.StoredState, policy: Policy) =
        policy.enabledOptionIds(state.optionIds, state.seenOptionIds)

    @Test
    fun `a default-on option stays on after it has been marked as offered`() {
        // What was written before the fix: only the seen set moved.
        val broken = existing.copy(seenOptionIds = listOf("whatsapp", "chatbots", "optin"))
        assertFalse(
            "the old write turned the arrival off on the next read",
            "chatbots" in read(broken, after),
        )

        val fixed = PolicyManager.withOfferedOptions(existing, after)
        assertTrue("chatbots must still be on on the next read", "chatbots" in read(fixed, after))
        // And on the read after that, and after a restart: nothing recomputes it.
        assertEquals(read(fixed, after), read(fixed, after))
    }

    @Test
    fun `an option that defaults to off is not switched on`() {
        val fixed = PolicyManager.withOfferedOptions(existing, after)
        assertFalse("optin" in read(fixed, after))
    }

    @Test
    fun `what the parent switched off stays off`() {
        // WhatsApp was switched off: the stored list is empty and WhatsApp was seen.
        val parentOff = PolicyStore.StoredState(
            optionIds = emptyList(),
            seenOptionIds = listOf("whatsapp"),
        )
        val next = PolicyManager.withOfferedOptions(parentOff, after)
        val enabled = read(next, after)
        assertFalse("whatsapp" in enabled)
        assertTrue("chatbots" in enabled)
    }

    @Test
    fun `a phone that never chose is left to the defaults`() {
        val untouched = PolicyStore.StoredState(optionIds = null, seenOptionIds = listOf("whatsapp"))
        val next = PolicyManager.withOfferedOptions(untouched, after)
        assertEquals(null, next.optionIds)
        assertEquals(setOf("whatsapp", "chatbots"), read(next, after))
    }

    @Test
    fun `a phone without a seen set keeps the old behaviour`() {
        // It predates the field; nothing is "new" to it, and nothing is switched on for it.
        val old = PolicyStore.StoredState(optionIds = listOf("whatsapp"), seenOptionIds = null)
        val next = PolicyManager.withOfferedOptions(old, after)
        assertEquals(listOf("whatsapp"), next.optionIds)
        assertTrue(next.seenOptionIds!!.containsAll(listOf("whatsapp", "chatbots", "optin")))
    }

    @Test
    fun `nothing is written when nothing arrives`() {
        val settled = PolicyStore.StoredState(
            optionIds = listOf("whatsapp", "chatbots"),
            seenOptionIds = listOf("whatsapp", "chatbots", "optin"),
        )
        assertSame(settled, PolicyManager.withOfferedOptions(settled, after))
    }
}
