package app.drawbridge.dpc.ui

import app.drawbridge.dpc.apps.BrowserSettings
import app.drawbridge.dpc.curfew.DisconnectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardPresetsTest {

    @Test
    fun `an option is allowed from its own recommended age`() {
        // WhatsApp's shield says 14, chatbots' says 18.
        assertFalse(WizardPresets.optionEnabled(WizardAge.UNDER_14, 14, current = true))
        assertTrue(WizardPresets.optionEnabled(WizardAge.AGE_14_16, 14, current = false))
        assertFalse(WizardPresets.optionEnabled(WizardAge.AGE_16_18, 18, current = true))
        assertTrue(WizardPresets.optionEnabled(WizardAge.ADULT, 18, current = false))
    }

    @Test
    fun `an option with no age keeps what it is set to`() {
        assertTrue(WizardPresets.optionEnabled(WizardAge.UNDER_14, null, current = true))
        assertFalse(WizardPresets.optionEnabled(WizardAge.ADULT, null, current = false))
    }

    @Test
    fun `browsers, installs and offline mode follow the age`() {
        assertEquals(BrowserSettings.Choice.NONE, WizardPresets.browsers(WizardAge.UNDER_14))
        assertEquals(BrowserSettings.Choice.MONO_ONLY, WizardPresets.browsers(WizardAge.AGE_14_16))
        assertEquals(BrowserSettings.Choice.ALL, WizardPresets.browsers(WizardAge.AGE_16_18))

        assertTrue(WizardPresets.installLock(WizardAge.UNDER_14))
        assertFalse(WizardPresets.installLock(WizardAge.AGE_14_16))

        assertEquals(DisconnectSettings.Mode.OFFLINE, WizardPresets.disconnect(WizardAge.UNDER_14))
        assertEquals(DisconnectSettings.Mode.CURFEW, WizardPresets.disconnect(WizardAge.AGE_14_16))
        assertEquals(DisconnectSettings.Mode.ONLINE, WizardPresets.disconnect(WizardAge.ADULT))
    }

    @Test
    fun `nothing restricted means nothing to lock for`() {
        fun needs(options: Boolean, b: BrowserSettings.Choice, install: Boolean, d: DisconnectSettings.Mode) =
            WizardPresets.requiresLocking(options, b, install, d)

        assertFalse(needs(true, BrowserSettings.Choice.ALL, false, DisconnectSettings.Mode.ONLINE))
        assertTrue(needs(false, BrowserSettings.Choice.ALL, false, DisconnectSettings.Mode.ONLINE))
        assertTrue(needs(true, BrowserSettings.Choice.NONE, false, DisconnectSettings.Mode.ONLINE))
        assertTrue(needs(true, BrowserSettings.Choice.ALL, true, DisconnectSettings.Mode.ONLINE))
        assertTrue(needs(true, BrowserSettings.Choice.ALL, false, DisconnectSettings.Mode.CURFEW))
    }
}
