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

    // The standalone browser: herald with no drawbridge behind it. Policy 133 ships
    // every option default-on, so following the defaults here would let YouTube and
    // the streaming services through the one browser a phone allows.

    private val allOnByDefault = document.copy(
        options = listOf(whatsapp.copy(defaultEnabled = true)),
    )
    private val heraldConfig = PolicyConfig(
        selectionSource = { null },
        optionsOffWithoutSelection = true,
    )
    private val nothingStored = { PolicyStore.StoredState() }

    @Test
    fun `with no drawbridge to answer, herald reads every option as off`() {
        val enabled = PolicyManager.resolveOptionIds(allOnByDefault, heraldConfig, null, nothingStored)

        assertEquals(emptySet<String>(), enabled)
        assertEquals(
            emptyList<String>(),
            allOnByDefault.effective("default", enabled).allowedDomains,
        )
    }

    @Test
    fun `an answer from drawbridge still wins, defaults and all`() {
        // Installed, nobody has chosen: drawbridge enforces the defaults at the DNS
        // layer, so the browser has to agree with it rather than be stricter.
        val unchosen = SelectionSource.Selection(profileId = null, optionIds = null)
        assertEquals(
            setOf("whatsapp"),
            PolicyManager.resolveOptionIds(allOnByDefault, heraldConfig, unchosen, nothingStored),
        )

        val switchedOff = SelectionSource.Selection(profileId = null, optionIds = emptyList())
        assertEquals(
            emptySet<String>(),
            PolicyManager.resolveOptionIds(allOnByDefault, heraldConfig, switchedOff, nothingStored),
        )
    }

    @Test
    fun `an app that owns its selection is untouched by the switch`() {
        // drawbridge has no selection source, so the flag means nothing to it and
        // a fresh install still starts from the document's defaults.
        val owner = PolicyConfig(optionsOffWithoutSelection = true)
        assertEquals(
            setOf("whatsapp"),
            PolicyManager.resolveOptionIds(allOnByDefault, owner, null, nothingStored),
        )
    }

    @Test
    fun `with no drawbridge to answer, herald applies every version-gated fragment`() {
        // The fragment is gated so an old drawbridge is not shown a switch it cannot
        // honour. With none installed there is no switch, and every option reads as off.
        assertEquals(Int.MAX_VALUE, PolicyManager.conditionalVersionCode(heraldConfig, null))
    }

    @Test
    fun `a drawbridge too old to publish its build still applies no fragment`() {
        val old = SelectionSource.Selection(profileId = null, optionIds = null)
        assertEquals(0, PolicyManager.conditionalVersionCode(heraldConfig, old))

        val build63 = old.copy(ownerVersionCode = 63)
        assertEquals(63, PolicyManager.conditionalVersionCode(heraldConfig, build63))
    }

    @Test
    fun `an app that owns its selection judges fragments by its own build`() {
        val owner = PolicyConfig(optionsOffWithoutSelection = true, ownVersionCode = 63)
        assertEquals(63, PolicyManager.conditionalVersionCode(owner, null))
    }
}
