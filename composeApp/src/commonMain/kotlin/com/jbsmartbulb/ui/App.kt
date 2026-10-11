package com.jbsmartbulb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.ConnectionState
import com.jbsmartbulb.SpeakerState
import com.jbsmartbulb.transport.BulbPlatform

private enum class Destination(val label: String, val icon: ImageVector) {
    Lamp("Lamp", Icons.Rounded.Lightbulb),
    Sound("Sound", Icons.Rounded.GraphicEq),
    Settings("Settings", Icons.Rounded.Tune),
    Devices("Devices", Icons.Rounded.Bluetooth),
}

/** Widths from the MD3 window size classes: rail from Medium (600dp), content capped on wide windows. */
private val MediumWidth = 600.dp
private val MaxContentWidth = 840.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartBulbApp(controller: BulbController, platform: BulbPlatform, host: PlatformHost) {
    SmartBulbTheme {
        val connection by controller.connection.collectAsState()
        val speaker by controller.speaker.collectAsState()
        // During the speaker hand-over the control link drops on purpose; stay where the user is.
        val handingOver = speaker == SpeakerState.Working
        val connected = connection is ConnectionState.Connected || handingOver
        val fadeStore = remember { FadeStore() }
        var destination by rememberSaveable { mutableStateOf(Destination.Devices) }
        val snackbar = remember { SnackbarHostState() }

        // Jump to the lamp once a bulb connects, back to the device list when it drops.
        // Saved, so a rotation does not count as a new connection.
        var wasConnected by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(connected) {
            if (connected != wasConnected) {
                destination = if (connected) Destination.Lamp else Destination.Devices
                wasConnected = connected
            }
        }
        LaunchedEffect(connection) {
            val failed = connection as? ConnectionState.Failed ?: return@LaunchedEffect
            snackbar.showSnackbar("${failed.device.name}: ${failed.reason}")
        }

        val destinations = if (connected) Destination.entries else listOf(Destination.Devices)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= MediumWidth && destinations.size > 1
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(if (destination == Destination.Devices) "Smart Bulb app" else destination.label) },
                    )
                },
                bottomBar = {
                    if (!useRail && destinations.size > 1) {
                        NavigationBar {
                            destinations.forEach { dest ->
                                NavigationBarItem(
                                    selected = destination == dest,
                                    onClick = { destination = dest },
                                    icon = { Icon(dest.icon, contentDescription = null) },
                                    label = { Text(dest.label) },
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (useRail) {
                        NavigationRail {
                            destinations.forEach { dest ->
                                NavigationRailItem(
                                    selected = destination == dest,
                                    onClick = { destination = dest },
                                    icon = { Icon(dest.icon, contentDescription = null) },
                                    label = { Text(dest.label) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) {
                            when (destination) {
                                Destination.Lamp -> LampScreen(controller, fadeStore)
                                Destination.Sound -> SoundScreen(controller, platform, host)
                                Destination.Settings -> SettingsScreen(controller)
                                Destination.Devices -> DevicesScreen(controller, platform, host)
                            }
                        }
                    }
                }
            }
        }
    }
}
