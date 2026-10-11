package com.jbsmartbulb.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameDecoderTest {
    // Frames a bulb would send, encoded with the ORIGINAL SDK encoder (see GoldenVectorsTest).
    private val lampReply = hex("01fe0000418210003456120051c80000")
    private val statusReply = hex("01fe0000410028000000000000000000020000110301000000000000000000000000000000000000")
    private val featureReply = hex("01fe0000410210001000000000000000")

    @Test fun decodesLampReply() {
        val frame = BluzFrameDecoder().feed(lampReply).single()
        assertEquals(0x4182, frame.key)
        val state = LampState.fromArgs(frame.arg1, frame.arg2)
        assertEquals(LampState(red = 0x12, green = 0x34, blue = 0x56, white = 200, mode = LampMode.Auto), state)
    }

    @Test fun decodesStatusPayload() {
        val frame = BluzFrameDecoder().feed(statusReply).single()
        assertEquals(BluzKeys.RSP_STATUS, frame.key)
        assertEquals(24, frame.payload.size)
        assertEquals(DeviceStatus(mode = 2, volume = 17, eqCode = 3, muted = true), DeviceStatus.parse(frame.payload))
    }

    @Test fun skipsGarbageAndHandlesByteByByteDelivery() {
        val stream = hex("00ff01") + featureReply + hex("01fe") + lampReply + statusReply
        val decoder = BluzFrameDecoder()
        val frames = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(BluzKeys.RSP_FEATURES, 0x4182, BluzKeys.RSP_STATUS), frames.map { it.key })
        assertEquals(0x10, frames[0].arg1)
    }

    @Test fun dropsFramesOutsideTheDeviceKeyFamilies() {
        // An echoed phone→bulb frame (0x53xx) must not be taken as a reply.
        val frames = BluzFrameDecoder().feed(BulbCommands.setLamp(LampState.white(10)) + lampReply)
        assertEquals(listOf(0x4182), frames.map { it.key })
    }

    @Test fun roundTripsEncoderOutput() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = BluzFrameDecoder().feed(BluzFrame.encode(0x4123, -5, 77, payload)).single()
        assertEquals(-5, frame.arg1)
        assertEquals(77, frame.arg2)
        // Payload is read up to the padded length, as BluzTransceiver does.
        assertContentEquals(byteArrayOf(1, 2, 3, 0), frame.payload)
        assertTrue(frame.key in 0x4100..0x41FF)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private inline fun ByteArray.flatMap(block: (Byte) -> List<BluzFrame>): List<BluzFrame> =
        buildList { for (b in this@flatMap) addAll(block(b)) }
}
