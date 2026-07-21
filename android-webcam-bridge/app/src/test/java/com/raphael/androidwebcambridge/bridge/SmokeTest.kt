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
}
