package com.jbsmartbulb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LampColorsTest {
    @Test fun coldEndIsNeutralWhiteAndWarmEndIsAmber() {
        // Reference values of the Helland approximation at the two slider ends.
        assertEquals(Triple(255, 254, 250), LampColors.kelvinToRgb(6500))
        assertEquals(Triple(255, 167, 87), LampColors.kelvinToRgb(2700))
    }

    @Test fun whiteDrivesTheWhiteLedAtFullBrightness() {
        val warm = LampColors.white(temperature = 1f, brightness = 1f)
        assertEquals(LampState(255, 167, 87, 255, LampMode.Manual), warm)
        val dim = LampColors.white(temperature = 0f, brightness = 0.5f)
        assertEquals(128, dim.white)
        assertEquals(128, dim.red)
    }

    @Test fun colourKeepsTheWhiteLedOff() {
        assertEquals(LampState.color(255, 0, 0), LampColors.colour(0f, 1f))
        assertEquals(LampState.color(0, 0, 255), LampColors.colour(240f, 1f))
        assertEquals(LampState.color(0, 128, 0), LampColors.colour(120f, 0.5f))
    }

    @Test fun describeRecoversTheControlsFromABulbReport() {
        val sent = LampColors.white(temperature = 0.8f, brightness = 0.6f)
        val controls = LampColors.describe(sent)
        assertTrue(controls.isWhite)
        assertEquals(0.8f, controls.temperature, 0.05f)
        assertEquals(0.6f, controls.brightness, 0.01f)

        val colour = LampColors.describe(LampColors.colour(200f, 1f))
        assertFalse(colour.isWhite)
        assertEquals(200f, colour.hue, 1.5f)
    }

    @Test fun oldAppWhiteWithoutTintReadsAsNeutral() {
        // What the original app sent from its white slider: RGB 0, white LED only.
        val controls = LampColors.describe(LampState.white(180))
        assertTrue(controls.isWhite)
        assertEquals(0.5f, controls.temperature)
    }
}
