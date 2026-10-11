package com.jbsmartbulb.transport

import com.jbsmartbulb.protocol.BluzFrame
import com.jbsmartbulb.protocol.BluzFrameDecoder
import com.jbsmartbulb.protocol.BluzKeys
import com.jbsmartbulb.protocol.DeviceStatus
import com.jbsmartbulb.protocol.LampState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A simulated bulb that speaks the device side of the protocol. It exists so the UI and the
 * connection flow can be exercised without hardware; it proves nothing about the real bulb.
 */
class DemoBulbPlatform(private val delegate: BulbPlatform? = null) : BulbPlatform {
    override fun isBluetoothOn(): Boolean = delegate?.isBluetoothOn() ?: true

    override fun pairedDevices(): List<BulbDevice> = listOf(DEMO_DEVICE) + delegate?.pairedDevices().orEmpty()

    override fun discover(): Flow<BulbDevice> = delegate?.discover() ?: emptyFlow()

    override val supportsSpeaker: Boolean get() = delegate?.supportsSpeaker ?: false

    override fun isSpeakerConnected(device: BulbDevice): Boolean =
        device.id != DEMO_DEVICE.id && delegate?.isSpeakerConnected(device) == true

    override suspend fun connectSpeaker(device: BulbDevice) {
        if (device.id == DEMO_DEVICE.id) throw UnsupportedOperationException("the demo bulb has no speaker")
        delegate?.connectSpeaker(device) ?: throw UnsupportedOperationException("not supported here")
    }

    override suspend fun connect(device: BulbDevice): BulbTransport {
        if (device.id != DEMO_DEVICE.id) {
            return delegate?.connect(device) ?: error("unknown device ${device.id}")
        }
        delay(600)
        return DemoTransport()
    }

    private class DemoTransport : BulbTransport {
        override val link: String = "simulated"
        private val decoder = BluzFrameDecoder(BluzFrameDecoder.ANY_KEY)
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)
        private var lamp = LampState.white(180)
        private var volume = 16
        private var eq = 0
        private var pending: ByteArray? = null

        override suspend fun read(buffer: ByteArray): Int {
            val chunk = pending ?: replies.receiveCatching().getOrNull() ?: return -1
            val count = minOf(chunk.size, buffer.size)
            chunk.copyInto(buffer, 0, 0, count)
            pending = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            for (frame in decoder.feed(bytes)) reply(frame)
        }

        private fun reply(frame: BluzFrame) {
            when (frame.key) {
                BluzKeys.QUERY_FEATURES -> send(BluzKeys.RSP_FEATURES, 0, 0, null)
                BluzKeys.POLL_STATUS -> {
                    val status = ByteArray(DeviceStatus.SIZE)
                    status[3] = volume.toByte()
                    status[4] = eq.toByte()
                    send(BluzKeys.RSP_STATUS, 0, 0, status)
                }
                BluzKeys.LAMP_QUERY -> send(0x4182, lamp.arg1, lamp.arg2, ByteArray(0))
                BluzKeys.LAMP_SET -> lamp = LampState.fromArgs(frame.arg1, frame.arg2)
                BluzKeys.GLOBAL_SET -> when (frame.arg1) {
                    1 -> volume = frame.arg2
                    2 -> eq = frame.arg2
                }
            }
        }

        private fun send(key: Int, arg1: Int, arg2: Int, payload: ByteArray?) {
            replies.trySend(BluzFrame.encode(key, arg1, arg2, payload))
        }

        override fun close() {
            replies.close()
        }
    }

    companion object {
        val DEMO_DEVICE: BulbDevice = BulbDevice(id = "demo", name = "Demo bulb (simulated)", paired = true)
    }
}
