package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigRulesTest {

    @Test
    fun `a rename keeps the device id already in use`() {
        assertEquals("xk03h_30a4", ConfigRules.deviceIdForName("xk03h_30a4", "projecteur_chambre_3e9b"))
    }

    @Test
    fun `the device id is derived while it is still empty`() {
        assertEquals("projecteur_chambre_3e9b", ConfigRules.deviceIdForName("", "projecteur_chambre_3e9b"))
        assertEquals("projecteur_chambre_3e9b", ConfigRules.deviceIdForName("   ", "projecteur_chambre_3e9b"))
    }

    @Test
    fun `changing the device id retires the previous one`() {
        assertEquals("xk03h_30a4", ConfigRules.retiredDeviceId("xk03h_30a4", "projecteur_chambre_3e9b"))
    }

    @Test
    fun `a stable device id retires nothing`() {
        assertNull(ConfigRules.retiredDeviceId("same_id", "same_id"))
        assertNull(ConfigRules.retiredDeviceId("", "new_id"))
        assertNull(ConfigRules.retiredDeviceId("   ", "new_id"))
    }
}
