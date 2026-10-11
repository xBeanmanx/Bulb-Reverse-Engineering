package com.jbsmartbulb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Expected bytes were produced by running the ORIGINAL decompiled SDK encoder
 * (com.actions.ibluz.a.f$a / b$d / b$v) with the argument expressions copied verbatim from the
 * original app's fragments, so these tests compare the port against the old app, not against
 * our own reading of it.
 */
class GoldenVectorsTest {
    @Test fun lampRed() = check("01fe0000538310000000ff0050000000", BulbCommands.setLamp(LampState.color(255, 0, 0)))

    @Test fun lampOrangePreset() =
        check("01fe000053831000a500ff0050000000", BulbCommands.setLamp(LampState.color(0xff, 0xa5, 0x00)))

    @Test fun lampChannelOrderIsRedBlueGreen() =
        check("01fe0000538310003456120050000000", BulbCommands.setLamp(LampState.color(0x12, 0x34, 0x56)))

    @Test fun lampWhite128() = check("01fe0000538310000000000050800000", BulbCommands.setLamp(LampState.white(128)))

    @Test fun lampWhite255() = check("01fe0000538310000000000050ff0000", BulbCommands.setLamp(LampState.white(255)))

    @Test fun lampAuto() = check(
        "01fe000053831000ffff000051000000",
        BulbCommands.setLamp(LampState(0x00, 0xff, 0xff, 0, LampMode.Auto)),
    )

    @Test fun lampOff() = check("01fe0000538310000000000050000000", BulbCommands.setLamp(LampState.OFF))

    @Test fun sleepTimer() = check("01fe0000538110001815000018150000", BulbCommands.sleepTimer(5400))

    @Test fun sleepTimerCancel() = check("01fe0000538410000000000000000000", BulbCommands.cancelSleepTimer())

    @Test fun renameShortNameIsSpacePadded() = check(
        "01fe000053804c0000000000000000000342756c6220202000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        BulbCommands.rename("Bulb"),
    )

    @Test fun renameLongName() = check(
        "01fe000053804c000000000000000000034c6976696e6720726f6f6d206c616d7000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        BulbCommands.rename("Living room lamp"),
    )

    @Test fun lampQuery() = check("01fe0000518210000000000000000000", BulbCommands.queryLamp())

    @Test fun pollStatus() = check("01fe0000510010000000008000000080", BulbCommands.pollStatus())

    @Test fun queryFeatures() = check("01fe0000510210000000008000000080", BulbCommands.queryFeatures())

    @Test fun volume() = check("01fe0000530010000100000014000000", BulbCommands.setVolume(20))

    @Test fun eqPresetDbb() = check("01fe0000530010000200000005000000", BulbCommands.setEqPreset(EqPreset.Dbb))

    @Test fun eqBands() = check(
        "01fe0000530018000300000000000080f4fa000306090c00",
        BulbCommands.setEqBands(intArrayOf(-12, -6, 0, 3, 6, 9, 12)),
    )

    @Test fun systemTime() = check(
        "01fe0000530018000000000000000080ea070a0b0d2d0700",
        BulbCommands.setSystemTime(2026, 10, 11, 13, 45, 7),
    )

    @Test fun release() = check("01fe0000530110000000000000000080", BulbCommands.release())

    @Test fun handshake() = check("3031323334353637", BulbCommands.HANDSHAKE)

    private fun check(expectedHex: String, actual: ByteArray) = assertEquals(expectedHex, actual.toHex())
}
