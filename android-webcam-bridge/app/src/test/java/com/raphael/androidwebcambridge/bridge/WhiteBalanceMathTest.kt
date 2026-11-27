package com.raphael.androidwebcambridge.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceMathTest {
    @Test
    fun estimatesWarmerAndCoolerSamplesInOrder() {
        val warm = WhiteBalanceMath.temperatureForColor(255, 220, 180)!!
        val neutral = WhiteBalanceMath.temperatureForColor(220, 220, 220)!!
        val cool = WhiteBalanceMath.temperatureForColor(180, 220, 255)!!

        assertTrue(warm < neutral)
        assertTrue(neutral < cool)
        assertTrue(listOf(warm, neutral, cool).all { it in 2500..10000 && it % 100 == 0 })
    }

    @Test
    fun rejectsInvalidOrTooDarkSamples() {
        assertTrue(WhiteBalanceMath.temperatureForColor(-1, 200, 200) == null)
        assertTrue(WhiteBalanceMath.temperatureForColor(0, 0, 0) == null)
    }

    @Test
    fun referenceTemperatureKeepsAutomaticColorBalance() {
        val automatic = floatArrayOf(2f, 1.2f, 1.1f, 1.8f)

        val gains = WhiteBalanceMath.gainsForTemperature(5500, automatic)

        assertArrayEquals(floatArrayOf(2f, 1.2f, 1.1f, 1.8f), gains, 0.001f)
    }

    @Test
    fun temperatureAdjustmentsStayInRangeAndScaleColorChannels() {
        val automatic = floatArrayOf(2f, 1.2f, 1.1f, 1.8f)

        val lowTemperature = WhiteBalanceMath.gainsForTemperature(2500, automatic)
        val highTemperature = WhiteBalanceMath.gainsForTemperature(10000, automatic)

        assertTrue(lowTemperature[3] > automatic[3])
        assertTrue(highTemperature[0] > automatic[0])
        assertTrue((lowTemperature + highTemperature).all { it in 0.1f..10f })
    }
}
