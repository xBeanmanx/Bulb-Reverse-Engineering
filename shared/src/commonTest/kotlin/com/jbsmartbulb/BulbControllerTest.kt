@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jbsmartbulb

import com.jbsmartbulb.protocol.BluzFrame
import com.jbsmartbulb.protocol.BluzFrameDecoder
import com.jbsmartbulb.protocol.BluzKeys
import com.jbsmartbulb.protocol.BulbCommands
import com.jbsmartbulb.protocol.LampMode
import com.jbsmartbulb.protocol.LampState
import com.jbsmartbulb.protocol.toHex
import com.jbsmartbulb.transport.BulbDevice
import com.jbsmartbulb.transport.BulbPlatform
import com.jbsmartbulb.transport.BulbTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class BulbControllerTest {
    private val device = BulbDevice("AA:BB", "Bulb", paired = true)
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_790_000_000)
    }

    @Test
    fun sessionReplaysTheOriginalStartupSequence() = runTest {
        val bulb = ScriptedBulb(answerPolls = true)
        val controller = BulbController(SinglePlatform(bulb), backgroundScope, fixedClock)

        controller.connect(device)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertIs<ConnectionState.Connected>(controller.connection.value)
        val sent = bulb.written
        assertEquals(BulbCommands.HANDSHAKE.toHex(), sent[0].toHex(), "raw handshake first")
        val keys = BluzFrameDecoder(BluzFrameDecoder.ANY_KEY).feed(sent.drop(1).reduce { a, b -> a + b }).map { it.key }
        // features → clock → lamp query → refresh-rate burst (20 × current lamp state + a query) → poll
        assertEquals(listOf(BluzKeys.QUERY_FEATURES, BluzKeys.GLOBAL_SET, BluzKeys.LAMP_QUERY), keys.take(3))
        assertEquals(List(20) { BluzKeys.LAMP_SET } + BluzKeys.LAMP_QUERY + BluzKeys.POLL_STATUS, keys.drop(3).take(22))
        // The lamp query reply (0x4182) becomes the visible lamp state.
        assertEquals(LampState(10, 20, 30, 0, LampMode.Manual), controller.lamp.value)
    }

    @Test
    fun lampChangesAreSentAsLampSetFrames() = runTest {
        val bulb = ScriptedBulb(answerPolls = true)
        val controller = BulbController(SinglePlatform(bulb), backgroundScope, fixedClock)
        controller.connect(device)
        runCurrent()

        controller.setLamp(LampState.color(255, 0, 0))
        runCurrent()

        val last = BluzFrameDecoder(BluzFrameDecoder.ANY_KEY).feed(bulb.written.drop(1).reduce { a, b -> a + b }).last { it.key == BluzKeys.LAMP_SET }
        assertEquals(LampState.color(255, 0, 0).arg1, last.arg1)
    }

    @Test
    fun silentBulbIsDroppedAfter25MissedPolls() = runTest {
        val bulb = ScriptedBulb(answerPolls = false)
        val controller = BulbController(SinglePlatform(bulb), backgroundScope, fixedClock)
        controller.connect(device)
        runCurrent()
        advanceTimeBy(BulbController.FEATURE_TIMEOUT_MS + 24 * 1_400L)
        runCurrent()
        assertIs<ConnectionState.Connected>(controller.connection.value)

        advanceTimeBy(1_500)
        runCurrent()
        val state = controller.connection.value
        assertIs<ConnectionState.Failed>(state)
        assertTrue(bulb.closed)
    }

    private class SinglePlatform(private val transport: BulbTransport) : BulbPlatform {
        override fun isBluetoothOn() = true
        override fun pairedDevices() = emptyList<BulbDevice>()
        override fun discover(): Flow<BulbDevice> = emptyFlow()
        override suspend fun connect(device: BulbDevice) = transport
    }

    /** Answers like a bulb would; [answerPolls] false simulates a bulb that went silent. */
    private class ScriptedBulb(private val answerPolls: Boolean) : BulbTransport {
        override val link = "scripted SPP"
        override val sendsHandshake = true
        val written = mutableListOf<ByteArray>()
        var closed = false
        private val decoder = BluzFrameDecoder(BluzFrameDecoder.ANY_KEY)
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun read(buffer: ByteArray): Int {
            val chunk = replies.receiveCatching().getOrNull() ?: return -1
            chunk.copyInto(buffer)
            return chunk.size
        }

        override suspend fun write(bytes: ByteArray) {
            written += bytes
            for (frame in decoder.feed(bytes)) {
                when (frame.key) {
                    BluzKeys.QUERY_FEATURES -> if (answerPolls) replies.trySend(BluzFrame.encode(BluzKeys.RSP_FEATURES, 0, 0))
                    BluzKeys.LAMP_QUERY -> replies.trySend(
                        BluzFrame.encode(0x4182, LampState(10, 20, 30).arg1, LampState(10, 20, 30).arg2, ByteArray(0)),
                    )
                    BluzKeys.POLL_STATUS -> if (answerPolls) replies.trySend(BluzFrame.encode(BluzKeys.RSP_STATUS, 0, 0, ByteArray(24)))
                }
            }
        }

        override fun close() {
            closed = true
            replies.close()
        }
    }
}
