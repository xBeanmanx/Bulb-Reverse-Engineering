package com.jbsmartbulb.protocol

import com.jbsmartbulb.protocol.BluzFrame.Companion.NO_ARG
import com.jbsmartbulb.protocol.BluzFrame.Companion.encode

/** Keys used by the bulb. Names are ours; values come from the decompiled app (see each comment). */
object BluzKeys {
    /** Status poll, sent every ~400 ms by the SDK (BluzClient k(20736)). Answered by [RSP_STATUS]. */
    const val POLL_STATUS: Int = 0x5100

    /** Feature bitmap query sent once on connect (k(20738)). Answered by [RSP_FEATURES]. */
    const val QUERY_FEATURES: Int = 0x5102

    /** Global setter; arg1 selects the field: 0 time, 1 volume, 2 EQ preset, 3 EQ bands. */
    const val GLOBAL_SET: Int = 0x5300

    /** Sent by BluzManager.release() when the app lets go of the device. */
    const val RELEASE: Int = 0x5301

    /** buildKey(3, 130): asks the bulb for its lamp state (MainActivity.createBluzManager). */
    const val LAMP_QUERY: Int = 0x5182

    /** buildKey(4, 128): rename the bulb's Bluetooth name (SettingFragment). */
    const val RENAME: Int = 0x5380

    /** buildKey(4, 129): switch off after arg1 (= arg2) seconds (SettingFragment). */
    const val SLEEP_TIMER: Int = 0x5381

    /** buildKey(4, 131): RGB colour, white level and mode (ColorFragment / OpenCloseFragment). */
    const val LAMP_SET: Int = 0x5383

    /** buildKey(4, 132): cancel the sleep timer (SettingFragment). */
    const val SLEEP_TIMER_CANCEL: Int = 0x5384

    const val RSP_STATUS: Int = 0x4100
    const val RSP_FEATURES: Int = 0x4102

    /** buildKey(5, 128..191): replies to the custom (0x5380..0x53BF / 0x5180..0x51BF) commands. */
    val RSP_CUSTOM_RANGE: IntRange = 0x4180..0x41BF

    internal const val GLOBAL_TIME = 0
    internal const val GLOBAL_VOLUME = 1
    internal const val GLOBAL_EQ_PRESET = 2
    internal const val GLOBAL_EQ_BANDS = 3
}

/** Lamp mode byte, the low byte of arg2 in [BluzKeys.LAMP_SET]. */
enum class LampMode(val code: Int) {
    /** "Custom" in the old app: the bulb shows the colour / white level it was sent. */
    Manual(0x50),

    /** "Auto" in the old app: the bulb cycles colours on its own. */
    Auto(0x51);

    companion object {
        fun fromCode(code: Int): LampMode = entries.firstOrNull { it.code == code } ?: Manual
    }
}

/**
 * The lamp has RGB LEDs plus a separate white LED. On the wire (light command 53 83):
 * `GG BB RR SP | EF WW YY FD` — green/blue/red, speed, effect (0x50 fixed), white, unused, fade.
 */
data class LampState(
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0,
    val white: Int = 0,
    val mode: LampMode = LampMode.Manual,
    /** FD byte: a ~1 s glide on the BL08A; the tested BL-05 ignores it and switches instantly. */
    val fade: Boolean = false,
) {
    val isOn: Boolean get() = mode == LampMode.Auto || red + green + blue + white > 0

    /** arg1 is packed R, B, G (sic): `(R << 16) + (B << 8) + G` in ColorFragment. */
    val arg1: Int get() = (red.byte() shl 16) + (blue.byte() shl 8) + green.byte()
    val arg2: Int get() = (if (fade) 1 shl 24 else 0) or (white.byte() shl 8) or mode.code

    companion object {
        val OFF: LampState = LampState()

        fun color(red: Int, green: Int, blue: Int): LampState = LampState(red, green, blue, 0, LampMode.Manual)

        fun white(level: Int): LampState = LampState(0, 0, 0, level, LampMode.Manual)

        /** Inverse of [arg1] / [arg2]; matches MainActivity.mOnCustomCommandListener. */
        fun fromArgs(arg1: Int, arg2: Int): LampState = LampState(
            red = (arg1 shr 16) and 0xFF,
            blue = (arg1 shr 8) and 0xFF,
            green = arg1 and 0xFF,
            white = (arg2 shr 8) and 0xFF,
            mode = LampMode.fromCode(arg2 and 0xFF),
            fade = (arg2 ushr 24) != 0,
        )
    }
}

/** EQ presets in the order of the old app's buttons, with the mode id each one sent. */
enum class EqPreset(val label: String, val code: Int) {
    Jazz("Jazz", 1),
    Pop("Pop", 2),
    Classic("Classic", 3),
    Dbb("DBB", 5),
    Rock("Rock", 6),
    Soft("Soft", 4);

    companion object {
        fun fromCode(code: Int): EqPreset? = entries.firstOrNull { it.code == code }
    }
}

/** Fields of the 24-byte status block the bulb returns for every [BluzKeys.POLL_STATUS]. */
data class DeviceStatus(
    val mode: Int,
    val volume: Int,
    val eqCode: Int,
    val muted: Boolean,
) {
    companion object {
        const val SIZE: Int = 24

        fun parse(payload: ByteArray): DeviceStatus? {
            if (payload.size < SIZE) return null
            return DeviceStatus(
                mode = payload[0].toInt() and 0xFF,
                volume = payload[3].toInt() and 0xFF,
                eqCode = payload[4].toInt() and 0xFF,
                muted = payload[5].toInt() != 0,
            )
        }
    }
}

/** Builders for every frame the phone sends. Byte-for-byte identical to the original app. */
object BulbCommands {
    const val VOLUME_MAX: Int = 31
    const val EQ_BAND_MIN: Int = -12
    const val EQ_BAND_MAX: Int = 12
    const val EQ_BAND_COUNT: Int = 7
    val EQ_BAND_LABELS: List<String> = listOf("80", "200", "500", "1K", "4K", "8K", "16K")

    /** Max UTF-8 bytes in a new Bluetooth name (57-byte payload minus the encoding byte). */
    const val NAME_MAX_BYTES: Int = 56

    /** Raw bytes written straight after the RFCOMM socket opens (BluzDeviceSpp.j()). */
    val HANDSHAKE: ByteArray = "01234567".encodeToByteArray()

    fun pollStatus(): ByteArray = encode(BluzKeys.POLL_STATUS, NO_ARG, NO_ARG)

    fun queryFeatures(): ByteArray = encode(BluzKeys.QUERY_FEATURES, NO_ARG, NO_ARG)

    fun release(): ByteArray = encode(BluzKeys.RELEASE, 0, NO_ARG)

    fun queryLamp(): ByteArray = encode(BluzKeys.LAMP_QUERY, 0, 0, BluzFrame.EMPTY)

    fun setLamp(state: LampState): ByteArray = encode(BluzKeys.LAMP_SET, state.arg1, state.arg2, BluzFrame.EMPTY)

    fun sleepTimer(seconds: Int): ByteArray {
        require(seconds > 0) { "sleep timer must be positive" }
        return encode(BluzKeys.SLEEP_TIMER, seconds, seconds, BluzFrame.EMPTY)
    }

    fun cancelSleepTimer(): ByteArray = encode(BluzKeys.SLEEP_TIMER_CANCEL, 0, 0, BluzFrame.EMPTY)

    fun setVolume(volume: Int): ByteArray =
        encode(BluzKeys.GLOBAL_SET, BluzKeys.GLOBAL_VOLUME, volume.coerceIn(0, VOLUME_MAX))

    fun setEqPreset(preset: EqPreset): ByteArray =
        encode(BluzKeys.GLOBAL_SET, BluzKeys.GLOBAL_EQ_PRESET, preset.code)

    /** Seven band gains in dB, -12..12, one signed byte each (80 Hz .. 16 kHz). */
    fun setEqBands(bands: IntArray): ByteArray {
        require(bands.size == EQ_BAND_COUNT) { "need $EQ_BAND_COUNT bands" }
        val payload = ByteArray(EQ_BAND_COUNT) { bands[it].coerceIn(EQ_BAND_MIN, EQ_BAND_MAX).toByte() }
        return encode(BluzKeys.GLOBAL_SET, BluzKeys.GLOBAL_EQ_BANDS, NO_ARG, payload)
    }

    /** Clock sync sent on connect (BluzManager.setSystemTime). [month] is 1-based. */
    fun setSystemTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray {
        val payload = byteArrayOf(
            (year % 256).toByte(), (year / 256).toByte(), month.toByte(), day.toByte(),
            hour.toByte(), minute.toByte(), second.toByte(), 0,
        )
        return encode(BluzKeys.GLOBAL_SET, BluzKeys.GLOBAL_TIME, NO_ARG, payload)
    }

    /** UTF-8 name, space-padded to at least 7 bytes, behind encoding byte 3 (= UTF-8 in the SDK). */
    fun rename(name: String): ByteArray {
        var bytes = name.encodeToByteArray()
        require(bytes.size <= NAME_MAX_BYTES) { "name longer than $NAME_MAX_BYTES bytes" }
        if (bytes.size < 7) bytes = (name + " ".repeat(7 - bytes.size)).encodeToByteArray()
        val payload = ByteArray(NAME_MAX_BYTES + 1)
        payload[0] = 3
        bytes.copyInto(payload, 1)
        return encode(BluzKeys.RENAME, 0, 0, payload)
    }
}

private fun Int.byte(): Int = coerceIn(0, 255)
