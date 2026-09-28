package app.drawbridge.policy.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block that only reaches builds able to do something about it.
 *
 * **The promise being kept is to the person who never updates.** A new option
 * that releases something the base policy blocks is fine on a build that has the
 * switch and a confiscation on a build that does not — and on a locked phone,
 * one that costs the key. A fragment older builds cannot parse is the only way
 * to say "block this, but only where there is a way back".
 */
class ConditionalPolicyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val chatbots = PolicyOption(
        id = "chatbots",
        name = "Allow AI chatbots",
        defaultEnabled = true,
        allowedDomains = listOf("chatgpt.com", "claude.ai"),
    )

    private val policy = Policy(
        version = 1,
        blockedDomains = listOf("example.invalid"),
        options = listOf(chatbots),
        conditional = listOf(
            ConditionalPolicy(
                minVersionCode = 53,
                comment = "Paired with the chatbots option, which build 53 is the first to offer.",
                blockedDomains = listOf("chatgpt.com", "claude.ai", "grok.com"),
                blockedPackages = listOf("com.openai.chatgpt"),
            ),
        ),
    )

    @Test
    fun `a build too old for the fragment is untouched by it`() {
        val effective = policy.effective(null, setOf("chatbots"), versionCode = 52)
        assertFalse("chatgpt.com" in effective.blockedDomains)
        assertFalse("grok.com" in effective.blockedDomains)
        assertEquals(listOf("example.invalid"), effective.blockedDomains)
    }

    @Test
    fun `a build new enough applies it`() {
        val effective = policy.effective(null, emptySet(), versionCode = 53)
        assertTrue("chatgpt.com" in effective.blockedDomains)
        assertTrue("grok.com" in effective.blockedDomains)
        assertTrue("com.openai.chatgpt" in effective.blockedPackages)
    }

    /**
     * The pair working together, which is the whole point: the fragment blocks,
     * the option releases, and the order of the two is what decides whether the
     * switch does anything at all.
     */
    @Test
    fun `the option releases what the fragment blocked`() {
        val on = policy.effective(null, setOf("chatbots"), versionCode = 53)
        assertTrue("chatgpt.com" in on.blockedDomains)
        assertTrue("chatgpt.com" in on.allowedDomains)

        val off = policy.effective(null, emptySet(), versionCode = 53)
        assertTrue("chatgpt.com" in off.blockedDomains)
        assertFalse("chatgpt.com" in off.allowedDomains)
    }

    /** Grok is in the fragment and in no option, so nothing can release it. */
    @Test
    fun `a name no option names is blocked whatever the switch says`() {
        val on = policy.effective(null, setOf("chatbots"), versionCode = 53)
        assertTrue("grok.com" in on.blockedDomains)
        assertFalse("grok.com" in on.allowedDomains)
    }

    @Test
    fun `a document with no fragments is unchanged`() {
        val plain = policy.copy(conditional = emptyList())
        assertEquals(plain, plain.withConditionals(99))
    }

    /**
     * Zero is the resting state for a caller that has not thought about it — a
     * standalone browser with no drawbridge to ask. A fragment only ever adds a
     * block, so ignoring one filters less rather than more.
     */
    @Test
    fun `version zero applies nothing`() {
        assertEquals(listOf("example.invalid"), policy.withConditionals(0).blockedDomains)
    }

    /**
     * The mechanism rests entirely on this: a build that predates the field must
     * ignore it rather than reject the document. Both parsers set
     * `ignoreUnknownKeys`, and this is the test that says so out loud.
     */
    @Test
    fun `a build that has never heard of the field still reads the document`() {
        val parsed = json.decodeFromString<Policy>(
            """
            {
              "version": 7,
              "blocked_domains": ["example.invalid"],
              "conditional": [
                { "min_version_code": 53, "blocked_domains": ["chatgpt.com"] }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(7, parsed.version)
        assertEquals(1, parsed.conditional.size)
        assertEquals(53, parsed.conditional.single().minVersionCode)
    }
}
