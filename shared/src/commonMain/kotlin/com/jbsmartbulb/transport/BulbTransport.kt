package com.jbsmartbulb.transport

import kotlinx.coroutines.flow.Flow

/** A bulb the platform can see. [id] is whatever the platform needs to reconnect (MAC, tty path). */
data class BulbDevice(
    val id: String,
    val name: String,
    val paired: Boolean,
)

/** An open byte stream to the bulb (an RFCOMM / SPP socket on Android). */
interface BulbTransport {
    /** Human-readable link type for the diagnostics log. */
    val link: String

    /** The raw "01234567" greeting is an SPP-only step of the SDK (BluzDeviceSpp.j()). */
    val sendsHandshake: Boolean get() = false

    /** Blocks until bytes arrive; returns the count read, or -1 at end of stream. */
    suspend fun read(buffer: ByteArray): Int

    suspend fun write(bytes: ByteArray)

    /** Must unblock a pending [read]. Safe to call more than once. */
    fun close()
}

/** What each platform supplies. The protocol and the UI are shared on top of this. */
interface BulbPlatform {
    /** False when the radio is off or missing; the UI then asks the user to turn it on. */
    fun isBluetoothOn(): Boolean

    fun pairedDevices(): List<BulbDevice>

    /** Discovers nearby devices; completes when the platform's discovery window ends. */
    fun discover(): Flow<BulbDevice>

    /** Pairs if needed and opens the control link. Throws on failure. */
    suspend fun connect(device: BulbDevice): BulbTransport

    /** Whether this platform can make the bulb its audio output from the app. */
    val supportsSpeaker: Boolean get() = false

    fun isSpeakerConnected(device: BulbDevice): Boolean = false

    /**
     * Pairs the bulb over Classic and waits for its audio (A2DP) to connect. Called with no control
     * link open. Throws with a user-readable message on failure.
     */
    suspend fun connectSpeaker(device: BulbDevice): Unit = throw UnsupportedOperationException("not supported here")
}
