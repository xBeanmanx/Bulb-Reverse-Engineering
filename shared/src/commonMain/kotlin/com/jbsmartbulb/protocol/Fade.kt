package com.jbsmartbulb.protocol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An sRGB colour, 0..255 per channel. */
data class Rgb(val red: Int, val green: Int, val blue: Int) {
    val hex: String get() = "#" + listOf(red, green, blue).joinToString("") { it.toString(16).padStart(2, '0') }.uppercase()

    companion object {
        /** Parses "#RRGGBB" or "RRGGBB" (case-insensitive); null if it is not a colour. */
        fun parse(text: String): Rgb? {
            val s = text.trim().removePrefix("#")
            if (s.length != 6 || s.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
            val v = s.toInt(16)
            return Rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }
    }
}

/**
 * A looping fade through [colors]: [fadeMs] is the time to go from one colour to the next.
 * The app plays it by streaming interpolated colours (the BL-05 ignores the FD fade byte that the
 * BL08A honours: on hardware, FD commands switched instantly).
 */
data class FadePreset(val colors: List<Rgb>, val fadeMs: Int = DEFAULT_FADE_MS) {
    init {
        require(colors.isNotEmpty()) { "a fade needs at least one colour" }
        require(fadeMs > 0) { "fade time must be positive" }
    }

    /** The colour [elapsedMs] into the loop. */
    fun colorAt(elapsedMs: Long): Rgb {
        if (colors.size == 1) return colors[0]
        val loop = fadeMs.toLong() * colors.size
        val t = ((elapsedMs % loop) + loop) % loop
        val index = (t / fadeMs).toInt()
        val fraction = FadeCurve.ease((t % fadeMs).toDouble() / fadeMs).toFloat()
        val from = colors[index]
        val to = colors[(index + 1) % colors.size]
        return Rgb(
            lerp(from.red, to.red, fraction),
            lerp(from.green, to.green, fraction),
            lerp(from.blue, to.blue, fraction),
        )
    }

    /** The lamp command for [elapsedMs], RGB scaled by [brightness] (0..1), white LED off. */
    fun lampAt(elapsedMs: Long, brightness: Float): LampState {
        val c = colorAt(elapsedMs)
        val level = brightness.coerceIn(0f, 1f)
        return LampState.color((c.red * level).roundToInt(), (c.green * level).roundToInt(), (c.blue * level).roundToInt())
    }

    companion object {
        const val DEFAULT_FADE_MS: Int = 3_000

        /** "Load default": the whole colour spectrum, every 60° of hue, looping back to red. */
        val SPECTRUM: FadePreset = FadePreset(
            colors = (0 until 6).map { i -> LampColors.hueToRgb(i * 60f).let { (r, g, b) -> Rgb(r, g, b) } },
            fadeMs = DEFAULT_FADE_MS,
        )
    }
}

private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).roundToInt()

/**
 * Timing curve of one colour-to-colour transition: the cumulative Gaussian (normal CDF) centred on
 * the midpoint, rescaled so it starts exactly at 0 and ends exactly at 1. It dwells on the start
 * colour, speeds up through the middle and settles on the next colour. With [SIGMA] = 0.15 of the
 * fade time, a 1000 ms fade is under 0.4 % of the way for the first 100 ms, under 5 % until 250 ms,
 * 50 % at 500 ms, and symmetric towards the end.
 */
object FadeCurve {
    const val SIGMA: Double = 0.15

    private val low = normalCdf(-0.5 / SIGMA)
    private val high = normalCdf(0.5 / SIGMA)

    /** Share of the way from one colour to the next after [t] (0..1) of the fade time. */
    fun ease(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return ((normalCdf((x - 0.5) / SIGMA) - low) / (high - low)).coerceIn(0.0, 1.0)
    }

    private fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    /** Abramowitz & Stegun 7.1.26, |error| < 1.5e-7 (the Kotlin stdlib has no erf). */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val a = abs(x)
        val k = 1.0 / (1.0 + 0.3275911 * a)
        val poly = k * (0.254829592 + k * (-0.284496736 + k * (1.421413741 + k * (-1.453152027 + k * 1.061405429))))
        return sign * (1.0 - poly * exp(-a * a))
    }
}
