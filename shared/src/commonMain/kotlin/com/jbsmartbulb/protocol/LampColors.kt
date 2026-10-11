package com.jbsmartbulb.protocol

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maps the app's controls (white temperature, colour hue, brightness) onto the bulb's channels.
 *
 * The protocol has no colour-temperature field: [BluzKeys.LAMP_SET] carries RGB plus ONE white LED
 * level. A white of a given temperature is therefore sent as the white LED at the chosen brightness
 * PLUS an RGB tint of that temperature. The old app never sent both at once, so how the firmware
 * mixes them is not yet confirmed on hardware; this is the one place to change if it does not.
 */
object LampColors {
    /** Slider position 0 = cold, 1 = warm. */
    const val COLD_KELVIN: Int = 6500
    const val WARM_KELVIN: Int = 2700

    fun kelvin(temperature: Float): Int =
        (COLD_KELVIN + (WARM_KELVIN - COLD_KELVIN) * temperature.coerceIn(0f, 1f)).roundToInt()

    /** Black-body colour of [kelvin] as sRGB 0..255 (Tanner Helland's approximation). */
    fun kelvinToRgb(kelvin: Int): Triple<Int, Int, Int> {
        val t = kelvin / 100.0
        val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return Triple(red.channel(), green.channel(), blue.channel())
    }

    /** Fully saturated colour for [hue] in degrees. */
    fun hueToRgb(hue: Float): Triple<Int, Int, Int> {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f)
            1 -> Triple(x, 1f, 0f)
            2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f)
            4 -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        return Triple((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
    }

    fun white(temperature: Float, brightness: Float): LampState {
        val level = brightness.coerceIn(0f, 1f)
        val (r, g, b) = kelvinToRgb(kelvin(temperature))
        return LampState(r.scale(level), g.scale(level), b.scale(level), white = 255.scale(level), mode = LampMode.Manual)
    }

    fun colour(hue: Float, brightness: Float): LampState {
        val level = brightness.coerceIn(0f, 1f)
        val (r, g, b) = hueToRgb(hue)
        return LampState(r.scale(level), g.scale(level), b.scale(level), white = 0, mode = LampMode.Manual)
    }

    /** What the controls should show for a state reported by the bulb. */
    data class Controls(val isWhite: Boolean, val temperature: Float, val hue: Float, val brightness: Float)

    fun describe(state: LampState): Controls {
        val maxRgb = maxOf(state.red, state.green, state.blue)
        if (state.white > 0) {
            // Find the temperature whose tint has the reported blue/red balance; neutral if untinted.
            val temperature = if (state.red == 0) 0.5f else {
                val ratio = state.blue.toFloat() / state.red
                (0..100).map { it / 100f }.minBy { t ->
                    val (r, _, b) = kelvinToRgb(kelvin(t))
                    abs(b.toFloat() / r - ratio)
                }
            }
            return Controls(true, temperature, 0f, state.white / 255f)
        }
        if (maxRgb == 0) return Controls(true, 0.5f, 0f, 1f)
        return Controls(false, 0.5f, hueOf(state.red, state.green, state.blue), maxRgb / 255f)
    }

    private fun hueOf(r: Int, g: Int, b: Int): Float {
        val max = maxOf(r, g, b)
        val delta = (max - minOf(r, g, b)).toFloat()
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return (hue + 360f) % 360f
    }

    private fun Double.channel(): Int = roundToInt().coerceIn(0, 255)

    private fun Int.scale(level: Float): Int = (this * level).roundToInt().coerceIn(0, 255)
}
