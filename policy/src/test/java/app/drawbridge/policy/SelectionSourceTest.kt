package app.drawbridge.policy

import androidx.test.core.app.ApplicationProvider
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
import app.drawbridge.policy.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule that decides what a browser on a managed device actually enforces:
 * an external selection wins over this app's own stored state, and an absent one
 * falls back to it.
 *
 * Exercised through [Policy] rather than [PolicyManager], which needs a signed
 * document on disk to have a baseline at all. What is worth pinning down here is
 * the *semantics* of the three answers a source can give — a selection, an empty
 * selection, and no answer — because two of them look alike and mean opposite
 * things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SelectionSourceTest {

    private val whatsapp = PolicyOption(
        id = "whatsapp",
        name = "Allow WhatsApp",
        exemptPackages = listOf("com.whatsapp"),
        allowedDomains = listOf("whatsapp.com"),
    )

    private val document = Policy(
        version = 17,
        blockedDomains = listOf("whatsapp.com"),
        profiles = listOf(Profile(id = "default", name = "Default policy")),
        defaultProfile = "default",
        options = listOf(whatsapp),
    )

    @Test
    fun `the switch decides whether WhatsApp Web loads`() {
        val off = document.effective("default", document.enabledOptionIds(emptyList()))
        val on = document.effective("default", document.enabledOptionIds(listOf("whatsapp")))

        // The base document blocks it, which is what the standalone browser and
        // a device with the option off both see.
        assertEquals(emptyList<String>(), off.allowedDomains)
        assertEquals(listOf("whatsapp.com"), off.blockedDomains)

        // Turning it on adds an allow rule, and allow beats block.
        assertEquals(listOf("whatsapp.com"), on.allowedDomains)
        assertEquals(listOf("whatsapp.com"), on.blockedDomains)
    }

    @Test
    fun `no answer is not the same as an empty answer`() {
        // A browser that cannot reach drawbridge gets null and falls back to the
        // document's defaults. A browser that reaches it and is told "nothing is
        // on" must not silently re-enable the default-on options.
        val enabledByDefault = document.copy(
            options = listOf(whatsapp.copy(defaultEnabled = true)),
        )

        assertEquals(setOf("whatsapp"), enabledByDefault.enabledOptionIds(null))
        assertEquals(emptySet<String>(), enabledByDefault.enabledOptionIds(emptyList()))
    }

    @Test
    fun `a source that answers overrides the app's own stored state`() = runTest {
        val store = PolicyStore(ApplicationProvider.getApplicationContext(), PolicyConfig())
        store.writeState(PolicyStore.StoredState(profileId = "default", optionIds = emptyList()))

        val external = SelectionSource {
            SelectionSource.Selection(profileId = "default", optionIds = listOf("whatsapp"))
        }

        // What PolicyManager does with the two, spelled out: the external source
        // is asked first and its answer is used whole.
        val chosen = external.read() ?: SelectionSource.Selection(
            store.readState().profileId,
            store.readState().optionIds,
        )

        assertEquals(listOf("whatsapp"), chosen.optionIds)
        assertEquals(
            listOf("whatsapp.com"),
            document.effective(chosen.profileId, document.enabledOptionIds(chosen.optionIds))
                .allowedDomains,
        )

        store.clear()
    }

    @Test
    fun `a source that cannot answer leaves the app's own state in charge`() {
        val store = PolicyStore(ApplicationProvider.getApplicationContext(), PolicyConfig())
        store.writeState(PolicyStore.StoredState(profileId = "default", optionIds = listOf("whatsapp")))

        val absent = SelectionSource { null }

        val chosen = absent.read() ?: SelectionSource.Selection(
            store.readState().profileId,
            store.readState().optionIds,
        )

        assertEquals(listOf("whatsapp"), chosen.optionIds)
        store.clear()
    }
}
