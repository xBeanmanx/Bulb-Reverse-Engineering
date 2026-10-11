package com.jbsmartbulb.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BleFramingTest {
    @Test fun aFrameIsSentAsStartLengthDataEnd() {
        val frame = BulbCommands.setLamp(LampState.white(255)) // 16 bytes
        val writes = BleFraming.writesFor(frame)
        assertEquals(
            listOf(BleFraming.Target.Control, BleFraming.Target.Data, BleFraming.Target.Data, BleFraming.Target.Control),
            writes.map { it.first },
        )
        assertContentEquals(byteArrayOf(18, 0, 0, 0), writes[0].second) // DataBuffer.d() = L + 2
        assertContentEquals(byteArrayOf(16, 0), writes[1].second) // DataBuffer.b() = L
        assertContentEquals(frame, writes[2].second)
        assertContentEquals(byteArrayOf(0, 0, 2, 0), writes[3].second)
    }

    @Test fun longFramesAreChunkedAt80Bytes() {
        val frame = BulbCommands.rename("Living room lamp") // 76 bytes, one chunk
        assertEquals(4, BleFraming.writesFor(frame).size)
        val big = ByteArray(170) { it.toByte() }
        val chunks = BleFraming.writesFor(big).filter { it.first == BleFraming.Target.Data }.drop(1)
        assertEquals(listOf(80, 80, 10), chunks.map { it.second.size })
    }

    @Test fun decoderSkipsBlePacketPrefixesInTheRawStream() {
        // What arrives on 0x8888: per packet a 2-byte length prefix, then the frame, in MTU-sized pieces.
        val status = BluzFrame.encode(BluzKeys.RSP_STATUS, 0, 0, ByteArray(24)) // 40 bytes
        val lamp = BluzFrame.encode(0x4182, 0x0102, 0x5180, ByteArray(0)) // 16 bytes
        val raw = byteArrayOf(40, 0) + status + byteArrayOf(16, 0) + lamp
        val decoder = BluzFrameDecoder()
        val frames = raw.toList().chunked(20).flatMap { decoder.feed(it.toByteArray()) }
        assertEquals(listOf(BluzKeys.RSP_STATUS, 0x4182), frames.map { it.key })
        assertEquals(0x5180, frames[1].arg2)
    }
}
