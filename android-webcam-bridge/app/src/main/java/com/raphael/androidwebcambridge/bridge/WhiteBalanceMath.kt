package com.raphael.androidwebcambridge.bridge

import kotlin.math.ln
import kotlin.math.pow

internal object WhiteBalanceMath {
    fun temperatureForColor(red: Int, green: Int, blue: Int): Int? {
        if (red !in 0..255 || green !in 0..255 || blue !in 0..255) return null
        if ((red + green + blue) / 3 !in 24..245 || green == 0 || blue == 0) return null

        val targetRatio = red.toFloat() / blue
        var low = MIN_KELVIN
        var high = MAX_KELVIN
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (redBlueRatioAtTemperature(middle) > targetRatio) {
                low = middle
            } else {
                high = middle
            }
        }
        return (((low + high) / 2 + 50) / 100 * 100).coerceIn(MIN_KELVIN, MAX_KELVIN)
    }

    fun gainsForTemperature(
        kelvin: Int,
        automaticGains: FloatArray,
    ): FloatArray {
        require(automaticGains.size == CHANNEL_COUNT)
        val requested = gainsAtTemperature(kelvin)
        val reference = gainsAtTemperature(REFERENCE_KELVIN)
        return FloatArray(CHANNEL_COUNT) { index ->
            (automaticGains[index] * requested[index] / reference[index]).coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }

    private fun redBlueRatioAtTemperature(kelvin: Int): Float {
        val temperature = kelvin / 100f
        val red: Double
        val blue: Double
        if (temperature <= 66f) {
            red = 255.0
            blue = if (temperature <= 19f) 0.0 else 138.5177312231 * ln((temperature - 10f).toDouble()) - 305.0447927307
        } else {
            red = 329.698727446 * (temperature - 60f).pow(-0.1332047592f)
            blue = 255.0
        }
        return (red / blue).toFloat()
    }

    private fun gainsAtTemperature(kelvin: Int): FloatArray {
        val temperature = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN) / 100f
        val (red, green, blue) =
            if (temperature <= 66f) {
                Triple(
                    255.0,
                    99.4708025861 * ln(temperature.toDouble()) - 161.1195681661,
                    if (temperature <= 19f) 0.0 else 138.5177312231 * ln((temperature - 10f).toDouble()) - 305.0447927307,
                )
            } else {
                Triple(
                    329.698727446 * (temperature - 60f).pow(-0.1332047592f),
                    288.1221695283 * (temperature - 60f).pow(-0.0755148492f),
                    255.0,
                )
            }
        val referenceGreen = 99.4708025861 * ln(65.0) - 161.1195681661
        val referenceBlue = 138.5177312231 * ln(55.0) - 305.0447927307
        return floatArrayOf(
            (255.0 / red).toFloat(),
            (referenceGreen / green).toFloat(),
            (referenceGreen / green).toFloat(),
            (referenceBlue / blue).toFloat(),
        )
    }

    private const val CHANNEL_COUNT = 4
    private const val MAX_GAIN = 10f
    private const val MAX_KELVIN = 10000
    private const val MIN_GAIN = 0.1f
    private const val MIN_KELVIN = 2500
    private const val REFERENCE_KELVIN = 5500
}
