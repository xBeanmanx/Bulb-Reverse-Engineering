package com.jbsmartbulb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FadeTest {
    private val red = Rgb(255, 0, 0)
    private val blue = Rgb(0, 0, 255)

    @Test fun parsesAndFormatsHex() {
        assertEquals(Rgb(0x12, 0xAB, 0xff), Rgb.parse("#12abFF"))
        assertEquals(Rgb(0x12, 0xAB, 0xff), Rgb.parse("12ABFF"))
        assertEquals("#12ABFF", Rgb(0x12, 0xAB, 0xff).hex)
        assertNull(Rgb.parse("#12AB"))
        assertNull(Rgb.parse("#12ABFG"))
    }

    @Test fun oneColourHolds() {
        val fade = FadePreset(listOf(red), 1000)
        assertEquals(red, fade.colorAt(0))
        assertEquals(red, fade.colorAt(123_456))
    }

    @Test fun fadesAlongTheCurveAndLoops() {
        val fade = FadePreset(listOf(red, blue), 1000)
        assertEquals(red, fade.colorAt(0))
        assertEquals(Rgb(254, 0, 1), fade.colorAt(100)) // 0.34 % of the way: still holding on red
        assertEquals(Rgb(128, 0, 128), fade.colorAt(500))
        assertEquals(Rgb(1, 0, 254), fade.colorAt(900)) // settled on blue
        assertEquals(blue, fade.colorAt(1000))
        assertEquals(Rgb(128, 0, 128), fade.colorAt(1500)) // back towards red
        assertEquals(red, fade.colorAt(2000))
    }

    @Test fun gaussianCurveDwellsAtBothEnds() {
        // Reference values from the exact normal CDF (Python math.erf), sigma 0.15.
        val expected = mapOf(0.0 to 0.0, 0.1 to 0.0034, 0.25 to 0.0474, 0.4 to 0.2523, 0.5 to 0.5, 0.75 to 0.9526, 0.9 to 0.9966, 1.0 to 1.0)
        for ((t, value) in expected) assertEquals(value, FadeCurve.ease(t), 0.0002, "ease($t)")
        // Monotonic and symmetric.
        var previous = -1.0
        for (i in 0..1000) {
            val v = FadeCurve.ease(i / 1000.0)
            assertTrue(v >= previous, "monotonic at $i")
            assertEquals(1.0 - v, FadeCurve.ease(1.0 - i / 1000.0), 1e-6)
            previous = v
        }
    }

    @Test fun brightnessScalesTheLampCommand() {
        val lamp = FadePreset(listOf(red)).lampAt(0, 0.5f)
        assertEquals(LampState.color(128, 0, 0), lamp)
    }

    @Test fun fadeFlagIsTheLastByteOfTheLightCommand() {
        // chsmartbulb protocol.md: "fade to blue 01fe000053831000 00 ff 00 00 50 00 00 01"
        assertEquals("01fe00005383100000ff000050000001", BulbCommands.setLamp(LampState.color(0, 0, 255).copy(fade = true)).toHex())
        assertEquals(true, LampState.fromArgs(LampState.white(9).copy(fade = true).arg1, LampState.white(9).copy(fade = true).arg2).fade)
    }

    @Test fun defaultIsTheWholeSpectrum() {
        assertEquals(
            listOf("#FF0000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF"),
            FadePreset.SPECTRUM.colors.map { it.hex },
        )
        assertEquals(FadePreset.DEFAULT_FADE_MS, FadePreset.SPECTRUM.fadeMs)
        // Halfway between magenta and red the loop is on its way back to red.
        assertEquals(Rgb(255, 0, 128), FadePreset.SPECTRUM.colorAt(5L * 3000 + 1500))
    }
}
