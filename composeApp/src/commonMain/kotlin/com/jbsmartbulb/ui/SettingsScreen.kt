@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.jbsmartbulb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.ConnectionState
import com.jbsmartbulb.protocol.BulbCommands
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val QuickTimers = listOf(15, 30, 60, 90, 120)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: BulbController) {
    val connection by controller.connection.collectAsState()
    val endsAt by controller.sleepTimerEndsAt.collectAsState()
    val log by controller.log.collectAsState()
    var showTimePicker by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SoundCard("Sleep timer") {
            val active = endsAt?.takeIf { it > now }
            Text(
                if (active == null) "Switch the bulb off automatically."
                else "Switches off at ${clockText(active)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTimers.forEach { minutes ->
                    AssistChip(
                        onClick = { controller.setSleepTimer(minutes * 60) },
                        label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60}h${if (minutes % 60 > 0) " ${minutes % 60}m" else ""}") },
                    )
                }
                AssistChip(onClick = { showTimePicker = true }, label = { Text("At a time…") })
            }
            if (active != null) {
                OutlinedButton(onClick = controller::cancelSleepTimer) { Text("Cancel timer") }
            }
        }

        RenameCard(controller)

        SoundCard("Connection") {
            val device = (connection as? ConnectionState.Connected)?.device
            Text(
                device?.let { "${it.name}  ·  ${it.id}" } ?: "Not connected",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (device != null) {
                FilledTonalButton(onClick = controller::disconnect) { Text("Disconnect") }
            }
        }

        SoundCard("Diagnostics") {
            Text(
                "Frames exchanged with the bulb (newest last). Useful when testing a new bulb model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState(), reverseScrolling = true)
                        .padding(8.dp),
                ) {
                    if (log.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
                    log.takeLast(60).forEach { entry ->
                        Text(
                            (if (entry.outgoing) "→ " else "← ") + entry.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (entry.outgoing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Switch off at") },
            text = { TimeInput(state) },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    controller.setSleepTimer(secondsUntil(state.hour, state.minute))
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameCard(controller: BulbController) {
    var name by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val bytes = name.encodeToByteArray().size
    val tooLong = bytes > BulbCommands.NAME_MAX_BYTES
    SoundCard("Bluetooth name") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; sent = false },
            label = { Text("New name") },
            singleLine = true,
            isError = tooLong,
            supportingText = {
                Text(if (sent) "Name sent to the bulb" else "$bytes / ${BulbCommands.NAME_MAX_BYTES} bytes")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = name.isNotBlank() && !tooLong,
                onClick = { controller.rename(name.trim()); sent = true },
            ) { Text("Rename") }
        }
    }
}

/** Same idea as the old SettingFragment: the next occurrence of HH:MM, in whole minutes from now. */
private fun secondsUntil(hour: Int, minute: Int): Int {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val nowMinutes = now.hour * 60 + now.minute
    var target = hour * 60 + minute - nowMinutes
    if (target <= 0) target += 24 * 60
    return target * 60 - now.second
}

private fun clockText(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
