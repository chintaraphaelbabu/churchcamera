// ponytail: single smoke test — BridgeSettings fromQuery + data class api
package com.raphael.androidwebcambridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test
    fun bridgeSettingsFromQuery() {
        val q = mapOf("iso" to "400", "focusAuto" to "false")
        val base = BridgeSettings()
        val updated = BridgeSettings.fromQuery(q, base)
        assertEquals(400, updated.iso)
        assertFalse(updated.focusAuto)
    }

    @Test
    fun formatShutterProducesNonEmpty() {
        val label = formatShutter(60)
        assertTrue(label.isNotBlank())
    }



    @Test
    fun relayUrlUsesResolvedDnsSdAddressAndPort() {
        assertEquals("http://192.168.1.25:3000", relayUrlForAddress("192.168.1.25", 3000))
        assertEquals("http://[fe80::1234%25wlan0]:3000", relayUrlForAddress("fe80::1234%wlan0", 3000))
    }

    @Test
    fun relayUrlRejectsMissingAddressOrInvalidPort() {
        assertEquals(null, relayUrlForAddress(null, 3000))
        assertEquals(null, relayUrlForAddress("192.168.1.25", 0))
    }
}
