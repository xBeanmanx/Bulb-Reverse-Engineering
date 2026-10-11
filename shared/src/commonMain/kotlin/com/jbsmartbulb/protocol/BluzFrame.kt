package com.jbsmartbulb.protocol

/**
 * Wire format of the Actions Semiconductor "iBluz" SPP protocol used by the JB Smart Bulb.
 *
 * Reverse engineered from JBSmartBlub 2.9 (com.actions.ibluz SDK, SPPData / BluzTransceiver):
 *
 * ```
 * offset size  field
 *  0     1     0x01        sync
 *  1     1     0xFE        sync
 *  2     1     segment type (0 = unsegmented)
 *  3     1     segment sequence (0 = unsegmented)
 *  4     2     key, BIG endian
 *  6     2     total frame length incl. this 16-byte header, little endian
 *  8     4     arg1, little endian
 * 12     4     arg2, little endian
 * 16     n     payload, then zero padding to a multiple of 4 (only when a payload array is sent)
 * ```
 *
 * The phone sends keys 0x5xxx; the bulb answers with 0x41xx (data) or 0x52xx (segment ack).
 */
class BluzFrame(
    val key: Int,
    val arg1: Int,
    val arg2: Int,
    val payload: ByteArray = EMPTY,
) {
    override fun toString(): String =
        "key=0x${key.toString(16)} arg1=$arg1 arg2=$arg2 payload=${payload.toHex()}"

    companion object {
        const val SYNC_0: Int = 0x01
        const val SYNC_1: Int = 0xFE
        const val HEADER_SIZE: Int = 16

        /** Payloads of 200+ bytes need the SDK's segmented transfer, which no bulb command uses. */
        const val MAX_UNSEGMENTED_PAYLOAD: Int = 199

        /** The SDK fills arguments a command does not use with Integer.MIN_VALUE. */
        const val NO_ARG: Int = Int.MIN_VALUE

        val EMPTY: ByteArray = ByteArray(0)

        /**
         * Encodes one frame exactly like the original SDK (SPPData.a). [payload] null means "no
         * payload array", which skips the 4-byte alignment; an empty array is equivalent here.
         */
        fun encode(key: Int, arg1: Int, arg2: Int, payload: ByteArray? = null): ByteArray {
            var length = HEADER_SIZE
            if (payload != null) {
                require(payload.size <= MAX_UNSEGMENTED_PAYLOAD) { "payload too large: ${payload.size}" }
                length += payload.size
                val bias = length and 3
                if (bias != 0) length += 4 - bias
            }
            val out = ByteArray(length)
            out[0] = SYNC_0.toByte()
            out[1] = SYNC_1.toByte()
            out[4] = (key shr 8).toByte()
            out[5] = key.toByte()
            out[6] = length.toByte()
            out[7] = (length shr 8).toByte()
            out.putIntLe(8, arg1)
            out.putIntLe(12, arg2)
            payload?.copyInto(out, HEADER_SIZE)
            return out
        }
    }
}

/**
 * Incremental parser for the bulb → phone byte stream; mirrors BluzTransceiver.run():
 * hunt for 01 FE, skip the two segment bytes, read the big-endian key and drop the frame unless
 * [accept] allows it (by default the 0x41xx data and 0x52xx segment-ack families the SDK accepts).
 * Segment acks carry no payload. Unlike the SDK, a false sync only skips one byte, so a real
 * frame right after line noise is not lost.
 */
class BluzFrameDecoder(private val accept: (key: Int) -> Boolean = ::isBulbReplyKey) {
    private val buffer = ArrayList<Byte>()

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<BluzFrame> {
        for (i in 0 until count) buffer.add(bytes[i])
        val frames = ArrayList<BluzFrame>()
        while (true) {
            val frame = tryParse() ?: break
            frames.add(frame)
        }
        return frames
    }

    /** Returns null when more bytes are needed; consumes garbage as it goes. */
    private fun tryParse(): BluzFrame? {
        while (true) {
            if (buffer.size < 2) return null
            if (u8(0) != BluzFrame.SYNC_0 || u8(1) != BluzFrame.SYNC_1) { drop(1); continue }
            if (buffer.size < 6) return null
            val key = (u8(4) shl 8) or u8(5)
            if (!accept(key)) { drop(1); continue }
            if (buffer.size < BluzFrame.HEADER_SIZE) return null
            val length = u8(6) or (u8(7) shl 8)
            val arg1 = intLe(8)
            val arg2 = intLe(12)
            if (key and 0xFF00 == ACK_FAMILY) {
                drop(BluzFrame.HEADER_SIZE)
                return BluzFrame(key, arg1, arg2)
            }
            val payloadSize = (length - BluzFrame.HEADER_SIZE).coerceAtLeast(0)
            if (buffer.size < BluzFrame.HEADER_SIZE + payloadSize) return null
            val payload = ByteArray(payloadSize) { buffer[BluzFrame.HEADER_SIZE + it] }
            drop(BluzFrame.HEADER_SIZE + payloadSize)
            return BluzFrame(key, arg1, arg2, payload)
        }
    }

    private fun u8(index: Int): Int = buffer[index].toInt() and 0xFF

    private fun intLe(index: Int): Int =
        u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16) or (u8(index + 3) shl 24)

    private fun drop(n: Int) {
        buffer.subList(0, n).clear()
    }

    companion object {
        private const val DATA_FAMILY = 0x4100
        private const val ACK_FAMILY = 0x5200

        fun isBulbReplyKey(key: Int): Boolean = key and 0xFF00 == DATA_FAMILY || key and 0xFF00 == ACK_FAMILY

        /** For code playing the bulb's side (simulator, tests): accepts the phone's 0x5xxx keys too. */
        val ANY_KEY: (Int) -> Boolean = { true }
    }
}

internal fun ByteArray.putIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
    this[offset + 2] = (value shr 16).toByte()
    this[offset + 3] = (value shr 24).toByte()
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
