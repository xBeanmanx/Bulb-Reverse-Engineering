package com.jbsmartbulb.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Android transports: BLE GATT first (the tested bulb), Classic SPP when the bulb is playing audio
 * or is Classic-only. The old app forced the A2DP audio link through hidden BluetoothA2dp.connect();
 * modern Android blocks that, so [connectSpeaker] bonds over Classic and lets the system connect audio.
 *
 * Callers must hold BLUETOOTH_CONNECT (and BLUETOOTH_SCAN for [discover]) on Android 12+, or
 * location on Android 6–11 for discovery; the app requests them before using this class.
 */
@SuppressLint("MissingPermission")
class AndroidBulbPlatform(context: Context) : BulbPlatform {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Public A2DP proxy: only used to read which devices have audio connected. */
    @Volatile private var a2dp: BluetoothA2dp? = null

    init {
        adapter?.getProfileProxy(
            this.context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy as BluetoothA2dp
                }

                override fun onServiceDisconnected(profile: Int) {
                    a2dp = null
                }
            },
            BluetoothProfile.A2DP,
        )
    }

    override fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    override val supportsSpeaker: Boolean get() = true

    override fun isSpeakerConnected(device: BulbDevice): Boolean =
        a2dp?.connectedDevices.orEmpty().any { it.address == device.id }

    override suspend fun connectSpeaker(device: BulbDevice) = withContext(Dispatchers.IO) {
        val bt = adapter ?: throw IOException("This device has no Bluetooth")
        bt.cancelDiscovery()
        val remote = bt.getRemoteDevice(device.id)
        if (remote.bondState != BluetoothDevice.BOND_BONDED) {
            // No LE link is open (the controller dropped it), so this bonds over BR/EDR.
            pair(remote)
        }
        // Android connects the audio profiles of a newly bonded speaker by itself; there is no
        // public API to ask for it. Wait for that, then point the user at Settings if it did not.
        val connected = withTimeoutOrNull(SPEAKER_TIMEOUT_MS) {
            while (!isSpeakerConnected(device)) delay(500)
        } != null
        if (!connected) {
            throw IOException("Paired, but the phone did not connect the bulb's audio. Tap the bulb in Bluetooth settings to connect it.")
        }
    }

    override fun pairedDevices(): List<BulbDevice> =
        adapter?.bondedDevices.orEmpty()
            .map { it.toBulbDevice() }
            .sortedBy { it.name.lowercase() }

    override fun discover(): Flow<BulbDevice> = callbackFlow {
        val bt = adapter ?: run { close(); return@callbackFlow }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.device() ?: return
                        // The old scan list also skipped devices without a name.
                        if (device.name != null) trySend(device.toBulbDevice())
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                    // If the radio goes off (or the stack restarts) DISCOVERY_FINISHED never comes.
                    BluetoothAdapter.ACTION_STATE_CHANGED ->
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) != BluetoothAdapter.STATE_ON) close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (bt.isDiscovering) bt.cancelDiscovery()
        if (!bt.startDiscovery()) close(IOException("Bluetooth discovery could not start"))
        awaitClose {
            bt.cancelDiscovery()
            context.unregisterReceiver(receiver)
        }
    }

    override suspend fun connect(device: BulbDevice): BulbTransport = withContext(Dispatchers.IO) {
        val bt = adapter ?: throw IOException("This device has no Bluetooth")
        if (!bt.isEnabled) throw IOException("Bluetooth is off")
        bt.cancelDiscovery()
        val remote = bt.getRemoteDevice(device.id)
        // While the bulb plays audio over Classic it does not take BLE; control goes over SPP then.
        if (isSpeakerConnected(device)) return@withContext SocketTransport(openSocket(remote))
        // The SDK has two links. Bulbs that advertise LE (like the one this was tested with, which
        // advertises the SDK's BLE service 0x6666 and does not answer Classic pages) use GATT;
        // Classic-only bulbs use SPP. Try BLE first for anything LE-capable, then fall back.
        val bleError = if (remote.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            try {
                return@withContext GattTransport.connect(context, remote)
            } catch (e: GattTransport.WrongLinkException) {
                // Only fall back when BLE is the wrong link. While a BLE link is up, createBond() on a
                // dual-mode bulb would bond over LE instead of Classic; Classic pairing for audio goes
                // through connectSpeaker(), which drops the BLE link first.
                e
            }
        } else null
        try {
            if (remote.bondState != BluetoothDevice.BOND_BONDED) pair(remote)
            SocketTransport(openSocket(remote))
        } catch (e: IOException) {
            if (bleError != null) e.addSuppressed(bleError)
            val message = "BLE: ${bleError?.message ?: "not tried"}; SPP: ${e.message}"
            android.util.Log.w("SmartBulb", message, e)
            throw IOException(message, e)
        }
    }

    private fun openSocket(remote: BluetoothDevice): BluetoothSocket {
        // The SDK used the insecure socket; some stacks only accept the secure one.
        val insecure = remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        return try {
            insecure.connect()
            insecure
        } catch (first: IOException) {
            runCatching { insecure.close() }
            val secure = remote.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                secure.connect()
                secure
            } catch (second: IOException) {
                runCatching { secure.close() }
                second.addSuppressed(first)
                throw IOException("Could not open the bulb's control channel (SPP)", second)
            }
        }
    }

    private suspend fun pair(remote: BluetoothDevice) {
        val bonded = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.device()?.address != remote.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when {
                        state == BluetoothDevice.BOND_BONDED -> trySend(0)
                        state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING ->
                            trySend(intent.getIntExtra(EXTRA_UNBOND_REASON, -1))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            if (remote.bondState == BluetoothDevice.BOND_BONDED) trySend(0)
            else if (remote.bondState == BluetoothDevice.BOND_NONE && !remote.createBond()) trySend(-1)
            awaitClose { context.unregisterReceiver(receiver) }
        }
        when (val reason = withTimeout(PAIRING_TIMEOUT_MS) { bonded.first() }) {
            0 -> Unit
            UNBOND_REASON_REMOTE_DEVICE_DOWN -> throw IOException(
                "The bulb's speaker did not answer. If it is connected to another phone or device as a " +
                    "speaker, disconnect it there; otherwise switch the bulb off and on and try again.",
            )
            else -> throw IOException("Pairing with ${remote.name ?: remote.address} failed (reason $reason)")
        }
    }

    private fun BluetoothDevice.toBulbDevice() = BulbDevice(
        id = address,
        name = name ?: address,
        paired = bondState == BluetoothDevice.BOND_BONDED,
    )

    private class SocketTransport(private val socket: BluetoothSocket) : BulbTransport {
        override val link: String = "Classic SPP (RFCOMM)"
        override val sendsHandshake: Boolean = true
        private val input = socket.inputStream
        private val output = socket.outputStream

        // Socket reads ignore thread interrupts; close() is what unblocks them.
        override suspend fun read(buffer: ByteArray): Int = runInterruptible(Dispatchers.IO) { input.read(buffer) }

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Serial Port Profile; the SDK's default (BluzDeviceSpp). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PAIRING_TIMEOUT_MS = 60_000L
        private const val SPEAKER_TIMEOUT_MS = 20_000L

        /** Hidden in the SDK but stable: BluetoothDevice.EXTRA_REASON / UNBOND_REASON_REMOTE_DEVICE_DOWN. */
        private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
        private const val UNBOND_REASON_REMOTE_DEVICE_DOWN = 4
    }
}

private fun Intent.device(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
