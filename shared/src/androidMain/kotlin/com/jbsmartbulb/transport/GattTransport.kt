package com.jbsmartbulb.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.jbsmartbulb.protocol.BleFraming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * The iBluz link over BLE GATT (the SDK's BluzDeviceBle), for bulbs exposing service 0x6666.
 *
 * Two layouts exist. The SDK's full one has length/control characteristics (9998, 9988) next to
 * the data ones and uses [BleFraming.writesFor]. The tested bulb (00:E0:4C, firmware "smart bulb")
 * only has the data characteristics 8888 (in) and 8877 (out); there raw frames are written directly
 * with response, after the SDK's "01234567" greeting. Measured on that bulb: without the greeting
 * it drops the link on the first command; with it, commands run and answers arrive on 8888.
 */
@SuppressLint("MissingPermission")
internal class GattTransport private constructor() : BulbTransport {
    private enum class Framing { Sdk, Raw }

    private var framing = Framing.Sdk
    override val link: String get() = "BLE GATT 6666/7777, ${framing.name.lowercase()} framing, chunk $chunkSize"

    override val sendsHandshake: Boolean get() = framing == Framing.Raw

    private lateinit var gatt: BluetoothGatt
    private lateinit var dataIn: BluetoothGattCharacteristic
    private lateinit var dataOut: BluetoothGattCharacteristic
    private var headerIn: BluetoothGattCharacteristic? = null
    private var controlOut: BluetoothGattCharacteristic? = null
    private var chunkSize = 20
    @Volatile private var received = 0
    @Volatile private var linkUp = true

    private val stream = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending: ByteArray? = null
    private val opLock = Mutex()

    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var discovered = CompletableDeferred<Int>()
    @Volatile private var mtu = CompletableDeferred<Int>()
    @Volatile private var opDone: CompletableDeferred<Int>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    linkUp = false
                    val error = IOException("BLE link lost (status $status)")
                    connected.completeExceptionally(error)
                    discovered.completeExceptionally(error)
                    opDone?.completeExceptionally(error)
                    stream.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else 23)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opDone?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming(c, value)
        }

        @Deprecated("Called instead of the 3-argument version before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") incoming(c, c.value ?: return)
        }
    }

    /**
     * Data bytes go straight into the stream: each packet's 2-byte length prefix is not a valid
     * 01 FE sync, so BluzFrameDecoder skips it while resyncing. That keeps us correct even if the
     * header (0x9998) length is counted differently than we assume; the header is only subscribed.
     */
    private fun incoming(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid == DATA_IN && value.isNotEmpty()) {
            received++
            Log.w(TAG, "rx ${c.uuid.short()}: ${value.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}")
            stream.trySend(value.copyOf())
        }
    }

    private suspend fun open(context: Context, device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: throw NoLeLinkException()
            check(gatt.discoverServices()) { "discoverServices refused" }
            val status = withTimeout(CONNECT_TIMEOUT_MS) { discovered.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("service discovery failed ($status)")
            val table = describeGatt(gatt)
            Log.w(TAG, "GATT table of ${device.address}: $table")
            val notify = gatt.getService(SERVICE_NOTIFY)
            val write = gatt.getService(SERVICE_WRITE)
            if (notify == null || write == null) throw NotIbluzBleException(table)
            dataIn = notify.getCharacteristic(DATA_IN) ?: throw IOException("missing 8888. GATT: $table")
            dataOut = write.getCharacteristic(DATA_OUT) ?: throw IOException("missing 8877. GATT: $table")
            headerIn = notify.getCharacteristic(HEADER_IN)
            controlOut = write.getCharacteristic(CONTROL_OUT)
            // Shorter connection interval: every colour write waits for its response, so this sets
            // how many colours per second BLE can carry (measured at session start).
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            // The tested bulb truncates indications to MTU - 3 bytes instead of splitting them, so the
            // default MTU (23) cuts its 28- and 40-byte answers. It accepts MTU 200 when asked.
            if (gatt.requestMtu(REQUESTED_MTU)) {
                val negotiated = withTimeoutOrNull(OP_TIMEOUT_MS) { mtu.await() } ?: 23
                chunkSize = (negotiated - 3).coerceIn(20, BleFraming.CHUNK_SIZE)
            }
            // Same order as the SDK: indications on 8888, then on 9998.
            enableIndications(dataIn)
            headerIn?.let { enableIndications(it) }

            if (headerIn == null || controlOut == null) framing = Framing.Raw
            Log.w(TAG, "connected: $link")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private suspend fun enableIndications(c: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: throw IOException("no CCCD on ${c.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        op("enable ${c.uuid}") {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.setValue(value)
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private suspend fun writeChar(c: BluetoothGattCharacteristic, value: ByteArray) {
        op("write ${c.uuid}") {
            if (Build.VERSION.SDK_INT >= 33) {
                val code = gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (code != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "writeCharacteristic refused, code $code")
                code == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.setValue(value)
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(c)
            }
        }
    }

    /** GATT allows one outstanding operation; this runs one and waits for its callback. */
    private suspend fun op(name: String, start: () -> Boolean) {
        if (!linkUp) throw IOException("$name: BLE link is down")
        val done = CompletableDeferred<Int>()
        opDone = done
        // The stack can report busy for a moment right after the previous callback; retry briefly.
        var started = start()
        var attempts = 0
        while (!started && attempts++ < 10) {
            delay(30)
            started = start()
        }
        if (!started) throw IOException("$name could not start")
        val status = withTimeout(OP_TIMEOUT_MS) { done.await() }
        // The SDK also accepted 13 (GATT_INVALID_ATTRIBUTE_LENGTH) on writes.
        if (status != BluetoothGatt.GATT_SUCCESS && status != 13) throw IOException("$name failed ($status)")
    }

    override suspend fun write(bytes: ByteArray) = opLock.withLock { send(bytes, framing) }

    private suspend fun send(bytes: ByteArray, mode: Framing) {
        when (mode) {
            Framing.Sdk -> for ((target, value) in BleFraming.writesFor(bytes)) {
                writeChar(if (target == BleFraming.Target.Control) controlOut!! else dataOut, value)
            }
            Framing.Raw -> sendChunked(bytes)
        }
    }

    private suspend fun sendChunked(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            writeChar(dataOut, bytes.copyOfRange(offset, end))
            offset = end
        }
    }


    override suspend fun read(buffer: ByteArray): Int {
        val chunk = pending ?: stream.receiveCatching().getOrNull() ?: return -1
        val count = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, count)
        pending = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
        return count
    }

    override fun close() {
        linkUp = false
        stream.close()
        if (::gatt.isInitialized) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** BLE is not the right link for this device: Classic SPP may still be. */
    open class WrongLinkException(message: String) : IOException(message)

    class NotIbluzBleException(table: String) : WrongLinkException("no iBluz BLE service 6666/7777. GATT: $table")

    class NoLeLinkException : WrongLinkException("no BLE connection within ${CONNECT_TIMEOUT_MS / 1000} s")

    /** e.g. "1800[2a00:R 2a01:R] ffe0[ffe1:R,W,Wn,N]" — service and characteristic UUIDs with properties. */
    private fun describeGatt(gatt: BluetoothGatt): String = gatt.services.joinToString(" ") { service ->
        service.uuid.short() + service.characteristics.joinToString(" ", "[", "]") { c ->
            val p = c.properties
            val flags = listOfNotNull(
                "R".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
                "W".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
                "Wn".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
                "N".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
                "I".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
            )
            "${c.uuid.short()}:${flags.joinToString(",")}"
        }
    }

    private fun UUID.short(): String {
        val s = toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s
    }

    companion object {
        private val SERVICE_NOTIFY: UUID = UUID.fromString(BleFraming.SERVICE_NOTIFY)
        private val SERVICE_WRITE: UUID = UUID.fromString(BleFraming.SERVICE_WRITE)
        private val DATA_IN: UUID = UUID.fromString(BleFraming.CHAR_DATA_IN)
        private val HEADER_IN: UUID = UUID.fromString(BleFraming.CHAR_HEADER_IN)
        private val DATA_OUT: UUID = UUID.fromString(BleFraming.CHAR_DATA_OUT)
        private val CONTROL_OUT: UUID = UUID.fromString(BleFraming.CHAR_CONTROL_OUT)
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val REQUESTED_MTU = 247
        private const val TAG = "SmartBulb"

        suspend fun connect(context: Context, device: BluetoothDevice): GattTransport =
            GattTransport().also { it.open(context, device) }
    }
}
