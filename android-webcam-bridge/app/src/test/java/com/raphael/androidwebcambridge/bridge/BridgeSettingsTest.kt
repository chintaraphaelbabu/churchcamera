// ponytail: focused JVM coverage for the pure BridgeSettings.fromQuery / formatShutter logic.
package com.raphael.androidwebcambridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSettingsTest {
    /**
     * Non-default base so "field absent keeps current value" is actually observable.
     */
    private fun baseSettings(): BridgeSettings =
        BridgeSettings(
            selectedCameraId = "lens-back",
            physicalZoomRatio = 2.5f,
            zoomRatio = 3.5f,
            panX = 0.25f,
            panY = -0.5f,
            faceFollowEnabled = true,
            faceFollowStrength = 0.65f,
            selectedFaceId = 5,
            exposureCompensation = -2,
            iso = 320,
            shutterSpeedMs = 120,
            whiteBalanceKelvin = 5600,
            focusDistanceDiopters = 1.75f,
            focusAuto = true,
            frameRate = 30,
            resolutionPreset = ResolutionPreset.P1080,
            jpegQuality = 80,
            bitrateMbps = 8,
            focusVelocity = 0.4f,
            zoomVelocity = 0.6f,
            screenDimEnabled = true,
        )

    // ---- empty / absent ----------------------------------------------------

    @Test
    fun emptyQueryReturnsEquivalentSettings() {
        val base = baseSettings()
        assertEquals(base, BridgeSettings.fromQuery(emptyMap(), base))
    }

    @Test
    fun absentFieldsKeepCurrentValues() {
        val base = baseSettings()
        val updated = BridgeSettings.fromQuery(mapOf("iso" to "100"), base)

        assertEquals(100, updated.iso)
        assertEquals(base.selectedCameraId, updated.selectedCameraId)
        assertEquals(base.physicalZoomRatio, updated.physicalZoomRatio, 0f)
        assertEquals(base.zoomRatio, updated.zoomRatio, 0f)
        assertEquals(base.panX, updated.panX, 0f)
        assertEquals(base.panY, updated.panY, 0f)
        assertEquals(base.faceFollowEnabled, updated.faceFollowEnabled)
        assertEquals(base.faceFollowStrength, updated.faceFollowStrength, 0f)
        assertEquals(base.selectedFaceId, updated.selectedFaceId)
        assertEquals(base.exposureCompensation, updated.exposureCompensation)
        assertEquals(base.shutterSpeedMs, updated.shutterSpeedMs)
        assertEquals(base.whiteBalanceKelvin, updated.whiteBalanceKelvin)
        assertEquals(base.focusDistanceDiopters, updated.focusDistanceDiopters, 0f)
        assertEquals(base.focusAuto, updated.focusAuto)
        assertEquals(base.frameRate, updated.frameRate)
        assertEquals(base.resolutionPreset, updated.resolutionPreset)
        assertEquals(base.jpegQuality, updated.jpegQuality)
        assertEquals(base.bitrateMbps, updated.bitrateMbps)
        assertEquals(base.focusVelocity, updated.focusVelocity, 0f)
        assertEquals(base.zoomVelocity, updated.zoomVelocity, 0f)
        assertEquals(base.screenDimEnabled, updated.screenDimEnabled)
    }

    // ---- numeric parsing ---------------------------------------------------

    @Test
    fun parsesIntegerFields() {
        val updated =
            BridgeSettings.fromQuery(
                mapOf(
                    "iso" to "800",
                    "shutterSpeedMs" to "250",
                    "exposureCompensation" to "-3",
                    "frameRate" to "60",
                    "jpegQuality" to "95",
                    "bitrateMbps" to "12",
                    "whiteBalanceKelvin" to "4300",
                ),
                baseSettings(),
            )

        assertEquals(800, updated.iso)
        assertEquals(250, updated.shutterSpeedMs)
        assertEquals(-3, updated.exposureCompensation)
        assertEquals(60, updated.frameRate)
        assertEquals(95, updated.jpegQuality)
        assertEquals(12, updated.bitrateMbps)
        assertEquals(4300, updated.whiteBalanceKelvin)
    }

    @Test
    fun parsesWhiteBalanceAutoValue() {
        val updated = BridgeSettings.fromQuery(mapOf("whiteBalanceKelvin" to "0"), baseSettings())

        assertEquals(0, updated.whiteBalanceKelvin)
    }

    @Test
    fun parsesFloatFields() {
        val updated =
            BridgeSettings.fromQuery(
                mapOf(
                    "zoomRatio" to "4.5",
                    "physicalZoomRatio" to "2.0",
                    "panX" to "-0.125",
                    "panY" to "0.75",
                    "focusDistanceDiopters" to "2.25",
                    "focusVelocity" to "0.35",
                    "zoomVelocity" to "0.45",
                ),
                baseSettings(),
            )

        assertEquals(4.5f, updated.zoomRatio, 0f)
        assertEquals(2.0f, updated.physicalZoomRatio, 0f)
        assertEquals(-0.125f, updated.panX, 0f)
        assertEquals(0.75f, updated.panY, 0f)
        assertEquals(2.25f, updated.focusDistanceDiopters, 0f)
        assertEquals(0.35f, updated.focusVelocity, 0f)
        assertEquals(0.45f, updated.zoomVelocity, 0f)
    }

    @Test
    fun parsesAndBoundsFaceFollowStrength() {
        val base = baseSettings()

        assertEquals(0.7f, BridgeSettings().faceFollowStrength, 0f)
        assertEquals(
            0.8f,
            BridgeSettings.fromQuery(mapOf("faceFollowStrength" to "0.8"), base).faceFollowStrength,
            0f,
        )
        assertEquals(
            0.1f,
            BridgeSettings.fromQuery(mapOf("faceFollowStrength" to "-1"), base).faceFollowStrength,
            0f,
        )
        assertEquals(
            1f,
            BridgeSettings.fromQuery(mapOf("faceFollowStrength" to "2"), base).faceFollowStrength,
            0f,
        )
    }

    @Test
    fun invalidIntegerFallsBackToCurrentValue() {
        val base = baseSettings()
        val updated =
            BridgeSettings.fromQuery(
                mapOf(
                    "iso" to "abc",
                    "shutterSpeedMs" to "",
                    "exposureCompensation" to "1.5",
                    "frameRate" to "not-a-number",
                    "jpegQuality" to "",
                    "bitrateMbps" to "8mbps",
                    "whiteBalanceKelvin" to "warm",
                ),
                base,
            )

        assertEquals(base.iso, updated.iso)
        assertEquals(base.shutterSpeedMs, updated.shutterSpeedMs)
        assertEquals(base.exposureCompensation, updated.exposureCompensation)
        assertEquals(base.frameRate, updated.frameRate)
        assertEquals(base.jpegQuality, updated.jpegQuality)
        assertEquals(base.bitrateMbps, updated.bitrateMbps)
        assertEquals(base.whiteBalanceKelvin, updated.whiteBalanceKelvin)
    }

    @Test
    fun invalidFloatFallsBackToCurrentValue() {
        val base = baseSettings()
        val updated =
            BridgeSettings.fromQuery(
                mapOf(
                    "zoomRatio" to "abc",
                    "physicalZoomRatio" to "",
                    "panX" to "left",
                    "panY" to "--1",
                    "focusDistanceDiopters" to "far",
                    "focusVelocity" to "",
                    "zoomVelocity" to "fast",
                ),
                base,
            )

        assertEquals(base.zoomRatio, updated.zoomRatio, 0f)
        assertEquals(base.physicalZoomRatio, updated.physicalZoomRatio, 0f)
        assertEquals(base.panX, updated.panX, 0f)
        assertEquals(base.panY, updated.panY, 0f)
        assertEquals(base.focusDistanceDiopters, updated.focusDistanceDiopters, 0f)
        assertEquals(base.focusVelocity, updated.focusVelocity, 0f)
        assertEquals(base.zoomVelocity, updated.zoomVelocity, 0f)
    }

    // ---- booleans ----------------------------------------------------------

    @Test
    fun faceFollowEnabledTruthyValues() {
        for (raw in listOf("true", "1")) {
            val updated = BridgeSettings.fromQuery(mapOf("faceFollowEnabled" to raw), baseSettings())
            assertTrue("expected $raw to enable faceFollowEnabled", updated.faceFollowEnabled)
        }
    }

    @Test
    fun faceFollowEnabledFalsyValues() {
        // Case-sensitive by design: only "true" and "1" are truthy.
        for (raw in listOf("false", "0", "TRUE", "yes", "on", "")) {
            val updated = BridgeSettings.fromQuery(mapOf("faceFollowEnabled" to raw), baseSettings())
            assertFalse("expected $raw to disable faceFollowEnabled", updated.faceFollowEnabled)
        }
    }

    @Test
    fun faceFollowEnabledAbsentKeepsCurrent() {
        assertTrue(BridgeSettings.fromQuery(emptyMap(), baseSettings()).faceFollowEnabled)
        assertFalse(BridgeSettings.fromQuery(emptyMap(), BridgeSettings()).faceFollowEnabled)
    }

    @Test
    fun focusAutoTruthyValues() {
        for (raw in listOf("true", "TRUE", "True", "1")) {
            val updated = BridgeSettings.fromQuery(mapOf("focusAuto" to raw), baseSettings())
            assertTrue("expected $raw to enable focusAuto", updated.focusAuto)
        }
    }

    @Test
    fun focusAutoFalsyValues() {
        for (raw in listOf("false", "0", "yes", "on", "")) {
            val updated = BridgeSettings.fromQuery(mapOf("focusAuto" to raw), baseSettings())
            assertFalse("expected $raw to disable focusAuto", updated.focusAuto)
        }
    }

    @Test
    fun focusAutoAbsentKeepsCurrent() {
        assertTrue(BridgeSettings.fromQuery(emptyMap(), baseSettings()).focusAuto)
        assertFalse(
            BridgeSettings.fromQuery(emptyMap(), BridgeSettings(focusAuto = false)).focusAuto,
        )
    }

    @Test
    fun screenDimEnabledTruthyValues() {
        for (raw in listOf("true", "1")) {
            val updated = BridgeSettings.fromQuery(mapOf("screenDimEnabled" to raw), BridgeSettings())
            assertTrue("expected $raw to enable screenDimEnabled", updated.screenDimEnabled)
        }
    }

    @Test
    fun screenDimEnabledFalsyValues() {
        for (raw in listOf("false", "0", "TRUE", "yes", "")) {
            val updated = BridgeSettings.fromQuery(mapOf("screenDimEnabled" to raw), baseSettings())
            assertFalse("expected $raw to disable screenDimEnabled", updated.screenDimEnabled)
        }
    }

    @Test
    fun screenDimEnabledAbsentKeepsCurrent() {
        assertTrue(BridgeSettings.fromQuery(emptyMap(), baseSettings()).screenDimEnabled)
        assertFalse(BridgeSettings.fromQuery(emptyMap(), BridgeSettings()).screenDimEnabled)
    }

    // ---- selectedFaceId ----------------------------------------------------

    @Test
    fun selectedFaceIdMinusOneBecomesNull() {
        val updated = BridgeSettings.fromQuery(mapOf("selectedFaceId" to "-1"), baseSettings())
        assertNull(updated.selectedFaceId)
    }

    @Test
    fun selectedFaceIdParsesPositiveId() {
        val updated = BridgeSettings.fromQuery(mapOf("selectedFaceId" to "7"), baseSettings())
        assertEquals(7, updated.selectedFaceId)
    }

    @Test
    fun selectedFaceIdInvalidFallsBackToCurrent() {
        val updated = BridgeSettings.fromQuery(mapOf("selectedFaceId" to "abc"), baseSettings())
        assertEquals(5, updated.selectedFaceId)
    }

    @Test
    fun selectedFaceIdInvalidWithNoCurrentBecomesNull() {
        val updated = BridgeSettings.fromQuery(mapOf("selectedFaceId" to "abc"), BridgeSettings())
        assertNull(updated.selectedFaceId)
    }

    // ---- resolutionPreset --------------------------------------------------

    @Test
    fun resolutionPresetDefaultsTo1080p() {
        assertEquals(ResolutionPreset.P1080, BridgeSettings().resolutionPreset)
    }

    @Test
    fun whiteBalanceSampleSetsManualTemperature() {
        val updated =
            BridgeSettings.fromQuery(
                mapOf("whiteBalanceSample" to "255,220,180"),
                baseSettings().copy(whiteBalanceKelvin = 0),
            )

        assertTrue(updated.whiteBalanceKelvin in 2500..10000)
    }

    @Test
    fun resolutionPresetParsesKnownName() {
        val updated = BridgeSettings.fromQuery(mapOf("resolutionPreset" to "P4K"), baseSettings())
        assertEquals(ResolutionPreset.P4K, updated.resolutionPreset)
    }

    @Test
    fun resolutionPresetInvalidFallsBackToCurrent() {
        val updated = BridgeSettings.fromQuery(mapOf("resolutionPreset" to "8K"), baseSettings())
        assertEquals(ResolutionPreset.P1080, updated.resolutionPreset)
    }

    // ---- selectedCameraId --------------------------------------------------

    @Test
    fun selectedCameraIdPresentValueUpdates() {
        val updated = BridgeSettings.fromQuery(mapOf("selectedCameraId" to "lens-front"), baseSettings())
        assertEquals("lens-front", updated.selectedCameraId)
    }

    @Test
    fun selectedCameraIdAbsentKeepsCurrent() {
        val updated = BridgeSettings.fromQuery(mapOf("iso" to "400"), baseSettings())
        assertEquals("lens-back", updated.selectedCameraId)
    }

    // ---- combined ----------------------------------------------------------

    @Test
    fun multipleFieldsInOneQueryUpdateTogether() {
        val updated =
            BridgeSettings.fromQuery(
                mapOf(
                    "selectedCameraId" to "lens-tele",
                    "iso" to "640",
                    "shutterSpeedMs" to "30",
                    "zoomRatio" to "2.0",
                    "faceFollowEnabled" to "true",
                    "faceFollowStrength" to "0.8",
                    "selectedFaceId" to "3",
                    "focusAuto" to "false",
                    "frameRate" to "25",
                    "resolutionPreset" to "P1440",
                    "screenDimEnabled" to "1",
                ),
                baseSettings(),
            )

        assertEquals("lens-tele", updated.selectedCameraId)
        assertEquals(640, updated.iso)
        assertEquals(30, updated.shutterSpeedMs)
        assertEquals(2.0f, updated.zoomRatio, 0f)
        assertTrue(updated.faceFollowEnabled)
        assertEquals(0.8f, updated.faceFollowStrength, 0f)
        assertEquals(3, updated.selectedFaceId)
        assertFalse(updated.focusAuto)
        assertEquals(25, updated.frameRate)
        assertEquals(ResolutionPreset.P1440, updated.resolutionPreset)
        assertTrue(updated.screenDimEnabled)
        // Untouched fields survive the update.
        assertEquals(baseSettings().panY, updated.panY, 0f)
        assertEquals(baseSettings().jpegQuality, updated.jpegQuality)
    }

    // ---- formatShutter -----------------------------------------------------

    @Test
    fun formatShutterZeroIsAuto() {
        assertEquals("AUTO", formatShutter(0))
    }

    @Test
    fun formatShutterFormatsPositiveMillis() {
        assertEquals("1ms", formatShutter(1))
        assertEquals("60ms", formatShutter(60))
        assertEquals("250ms", formatShutter(250))
        assertEquals("1000ms", formatShutter(1000))
    }

    @Test
    fun formatShutterNegativeValueIsInterpolatedVerbatim() {
        // The function only special-cases 0; negatives are not clamped.
        assertEquals("-5ms", formatShutter(-5))
        assertEquals("-1ms", formatShutter(-1))
    }

    @Test
    fun formatShutterExtremeValuesAreInterpolatedVerbatim() {
        assertEquals("${Int.MAX_VALUE}ms", formatShutter(Int.MAX_VALUE))
        assertEquals("${Int.MIN_VALUE}ms", formatShutter(Int.MIN_VALUE))
    }

    // ---- ResolutionPreset metadata ----------------------------------------

    @Test
    fun resolutionPresetLabelsAndDimensions() {
        assertEquals("720p", ResolutionPreset.P720.label)
        assertEquals(1280, ResolutionPreset.P720.width)
        assertEquals(720, ResolutionPreset.P720.height)

        assertEquals("1080p", ResolutionPreset.P1080.label)
        assertEquals(1920, ResolutionPreset.P1080.width)
        assertEquals(1080, ResolutionPreset.P1080.height)

        assertEquals("1440p", ResolutionPreset.P1440.label)
        assertEquals(2560, ResolutionPreset.P1440.width)
        assertEquals(1440, ResolutionPreset.P1440.height)

        assertEquals("4k", ResolutionPreset.P4K.label)
        assertEquals(3840, ResolutionPreset.P4K.width)
        assertEquals(2160, ResolutionPreset.P4K.height)
    }
}
