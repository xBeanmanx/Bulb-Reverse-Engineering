package com.jbsmartbulb.protocol

/**
 * How iBluz frames travel over BLE (BluzDeviceBle + DataBuffer in the original SDK).
 *
 * GATT layout:
 * - service 0x6666: char 0x8888 = bulb → phone data (indications), char 0x9998 = bulb → phone header
 * - service 0x7777: char 0x8877 = phone → bulb data, char 0x9988 = phone → bulb control
 *
 * Phone → bulb, per frame of L bytes, each write acknowledged before the next:
 *   control ← [L+2 lo, L+2 hi, 0, 0]   (start, DataBuffer.d() = L + 2)
 *   data    ← [L lo, L hi]             (length prefix)
 *   data    ← frame, in chunks of at most 80 bytes
 *   control ← [0, 0, 2, 0]             (end)
 *
 * Bulb → phone: a header [len lo, len hi, type, 0] on 0x9998 (type 0 = a packet of len bytes follows),
 * then the packet arrives on 0x8888 in pieces. Each packet starts with its own 2-byte length prefix;
 * the rest is part of the same 01 FE frame stream SPP carries (the frame decoder skips the prefix).
 */
object BleFraming {
    const val SERVICE_NOTIFY: String = "00006666-0000-1000-8000-00805f9b34fb"
    const val SERVICE_WRITE: String = "00007777-0000-1000-8000-00805f9b34fb"
    const val CHAR_DATA_IN: String = "00008888-0000-1000-8000-00805f9b34fb"
    const val CHAR_HEADER_IN: String = "00009998-0000-1000-8000-00805f9b34fb"
    const val CHAR_DATA_OUT: String = "00008877-0000-1000-8000-00805f9b34fb"
    const val CHAR_CONTROL_OUT: String = "00009988-0000-1000-8000-00805f9b34fb"

    const val CHUNK_SIZE: Int = 80

    enum class Target { Control, Data }

    /** The ordered writes that send one frame. */
    fun writesFor(frame: ByteArray): List<Pair<Target, ByteArray>> = buildList {
        val total = frame.size + 2
        add(Target.Control to byteArrayOf(total.toByte(), (total shr 8).toByte(), 0, 0))
        add(Target.Data to byteArrayOf(frame.size.toByte(), (frame.size shr 8).toByte()))
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + CHUNK_SIZE, frame.size)
            add(Target.Data to frame.copyOfRange(offset, end))
            offset = end
        }
        add(Target.Control to byteArrayOf(0, 0, 2, 0))
    }
}
