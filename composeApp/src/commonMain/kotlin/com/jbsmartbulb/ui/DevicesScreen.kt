package com.jbsmartbulb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.ConnectionState
import com.jbsmartbulb.transport.BulbDevice
import com.jbsmartbulb.transport.BulbPlatform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_LIMIT_MS = 30_000L

@Composable
fun DevicesScreen(controller: BulbController, platform: BulbPlatform, host: PlatformHost) {
    val granted by host.permissionsGranted.collectAsState()
    val connection by controller.connection.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val nearby = remember { mutableStateListOf<BulbDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val bluetoothOn = remember(granted, refresh) { platform.isBluetoothOn() }
    val paired = remember(granted, refresh, connection) {
        if (granted) runCatching { platform.pairedDevices() }.getOrDefault(emptyList()) else emptyList()
    }

    // Re-read paired devices whenever a connection attempt ends (pairing may have just happened).
    LaunchedEffect(connection) { if (connection !is ConnectionState.Connecting) refresh++ }
    // Notice when the user turns Bluetooth back on, without needing "Check again".
    LaunchedEffect(bluetoothOn) {
        while (!bluetoothOn) {
            delay(1_500)
            if (platform.isBluetoothOn()) refresh++
        }
    }

    fun startScan() {
        scanJob?.cancel()
        nearby.clear()
        scanning = true
        scanJob = scope.launch {
            runCatching {
                // Classic discovery lasts ~12 s; the cap only guards against a platform that never ends it.
                withTimeoutOrNull(SCAN_LIMIT_MS) {
                    platform.discover().collect { device ->
                        if (paired.none { it.id == device.id } && nearby.none { it.id == device.id }) nearby += device
                    }
                }
            }
            scanning = false
            refresh++
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!granted) {
            item {
                NoticeCard(
                    icon = Icons.Rounded.Bluetooth,
                    title = "Bluetooth permission needed",
                    body = "The app needs permission to find and connect to your bulb.",
                    action = "Allow" to host::requestPermissions,
                )
            }
            return@LazyColumn
        }
        if (!bluetoothOn) {
            item {
                NoticeCard(
                    icon = Icons.Rounded.BluetoothDisabled,
                    title = "Bluetooth is off",
                    body = "Turn on Bluetooth, then come back to this screen.",
                    action = host.openBluetoothSettings?.let { open -> "Open settings" to open },
                    secondary = "Check again" to { refresh++ },
                )
            }
            return@LazyColumn
        }

        item {
            Text(
                "Tap your bulb to connect. For music, also connect its audio in the phone's Bluetooth settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // A BLE-controlled bulb is usually not paired, so show the live one even when no scan is running.
        val live = when (val c = connection) {
            is ConnectionState.Connected -> c.device
            is ConnectionState.Connecting -> c.device
            else -> null
        }?.takeIf { d -> paired.none { it.id == d.id } && nearby.none { it.id == d.id } }
        if (live != null) {
            item { SectionHeader("This bulb") }
            item(key = "live-" + live.id) { DeviceRow(live, connection, controller) }
        }
        item { SectionHeader("Paired devices") }
        if (paired.isEmpty()) {
            item { EmptyHint("No paired devices yet. Scan below or pair the bulb in Bluetooth settings.") }
        }
        items(paired, key = { "p-" + it.id }) { device -> DeviceRow(device, connection, controller) }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Nearby devices", Modifier.weight(1f))
                if (scanning) {
                    TextButton(onClick = { scanJob?.cancel(); scanning = false }) { Text("Stop") }
                } else {
                    FilledTonalButton(onClick = ::startScan) {
                        Icon(Icons.AutoMirrored.Rounded.BluetoothSearching, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Scan")
                    }
                }
            }
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!scanning && nearby.none { found -> paired.none { it.id == found.id } }) {
            item { EmptyHint("Put the bulb in pairing mode and tap Scan. The scan takes about 12 seconds.") }
        }
        val unpaired = nearby.filter { found -> paired.none { it.id == found.id } }
        items(unpaired, key = { "n-" + it.id }) { device -> DeviceRow(device, connection, controller) }
        host.openBluetoothSettings?.let { open ->
            item {
                OutlinedButton(onClick = open, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Open Bluetooth settings")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BulbDevice, connection: ConnectionState, controller: BulbController) {
    val connecting = connection is ConnectionState.Connecting && connection.device.id == device.id
    val connected = connection is ConnectionState.Connected && connection.device.id == device.id
    val supporting = when {
        connected -> "Connected"
        connecting -> if (device.paired) "Connecting…" else "Pairing and connecting…"
        device.paired -> "Paired"
        else -> "Not paired"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        ListItem(
            modifier = Modifier.clickable(enabled = !connecting) {
                if (connected) controller.disconnect() else controller.connect(device)
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = {
                Icon(
                    if (connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Lightbulb,
                    contentDescription = null,
                )
            },
            headlineContent = { Text(device.name) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                when {
                    connecting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    connected -> Text("Disconnect", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
                secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            }
        }
    }
}
