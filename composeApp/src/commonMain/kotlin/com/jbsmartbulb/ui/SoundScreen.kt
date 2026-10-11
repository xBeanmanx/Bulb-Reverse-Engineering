package com.jbsmartbulb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.SpeakerState
import com.jbsmartbulb.transport.BulbPlatform
import com.jbsmartbulb.protocol.BulbCommands
import com.jbsmartbulb.protocol.EqPreset
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundScreen(controller: BulbController, platform: BulbPlatform, host: PlatformHost) {
    val status by controller.status.collectAsState()
    val speaker by controller.speaker.collectAsState()
    var volume by remember { mutableFloatStateOf(status?.volume?.toFloat() ?: 16f) }
    var dragging by remember { mutableStateOf(false) }

    // Follow the bulb's own volume (its buttons can change it) unless the user is dragging.
    LaunchedEffect(status?.volume) {
        if (!dragging) status?.volume?.let { volume = it.toFloat() }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SoundCard("Speaker volume") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                Slider(
                    value = volume,
                    onValueChange = { dragging = true; volume = it },
                    onValueChangeFinished = {
                        dragging = false
                        controller.setVolume(volume.roundToInt())
                    },
                    valueRange = 0f..BulbCommands.VOLUME_MAX.toFloat(),
                    steps = BulbCommands.VOLUME_MAX - 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Text("${volume.roundToInt()}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
            }
        }

        SoundCard("Equaliser preset") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPreset.entries.forEach { preset ->
                    val selected = status?.eqCode == preset.code
                    FilterChip(
                        selected = selected,
                        onClick = { controller.setEqPreset(preset) },
                        label = { Text(preset.label) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.width(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
        }

        SoundCard("Bulb speaker") {
            Text(
                when (val state = speaker) {
                    SpeakerState.Connected -> "Connected. Music from any app now plays on the bulb."
                    SpeakerState.Working -> "Connecting the speaker. The light control reconnects when it is done."
                    is SpeakerState.Failed -> state.reason
                    SpeakerState.Idle -> "Use the bulb as your phone's speaker. Light control keeps working alongside."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (speaker is SpeakerState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (platform.supportsSpeaker && speaker != SpeakerState.Connected) {
                    Button(onClick = controller::connectSpeaker, enabled = speaker != SpeakerState.Working) {
                        Text(if (speaker is SpeakerState.Failed) "Try again" else "Connect speaker")
                    }
                }
                if (speaker == SpeakerState.Working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                host.openBluetoothSettings?.let { open -> OutlinedButton(onClick = open) { Text("Bluetooth settings") } }
            }
        }
    }
}

@Composable
internal fun SoundCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
