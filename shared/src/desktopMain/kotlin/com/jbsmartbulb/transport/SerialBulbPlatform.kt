package com.jbsmartbulb.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Desktop transport: once the bulb is paired in the OS, macOS exposes its SPP service as
 * /dev/cu.<name> and Linux (after `rfcomm bind`) as /dev/rfcomm<N>. Pairing happens in the OS.
 * Not yet tested against a real bulb.
 */
class SerialBulbPlatform : BulbPlatform {
    override fun isBluetoothOn(): Boolean = true

    override fun pairedDevices(): List<BulbDevice> =
        File("/dev").listFiles().orEmpty()
            .filter { it.name.startsWith("cu.") || it.name.startsWith("rfcomm") }
            .filterNot { it.name in IGNORED }
            .map { BulbDevice(id = it.path, name = it.name.removePrefix("cu."), paired = true) }
            .sortedBy { it.name.lowercase() }

    override fun discover(): Flow<BulbDevice> = emptyFlow()

    override suspend fun connect(device: BulbDevice): BulbTransport = withContext(Dispatchers.IO) {
        FileTransport(FileInputStream(device.id), FileOutputStream(device.id))
    }

    private class FileTransport(
        private val input: FileInputStream,
        private val output: FileOutputStream,
    ) : BulbTransport {
        override val link: String = "serial (SPP)"
        override val sendsHandshake: Boolean = true

        override suspend fun read(buffer: ByteArray): Int = runInterruptible(Dispatchers.IO) { input.read(buffer) }

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    private companion object {
        val IGNORED = setOf("cu.Bluetooth-Incoming-Port", "cu.debug-console")
    }
}
