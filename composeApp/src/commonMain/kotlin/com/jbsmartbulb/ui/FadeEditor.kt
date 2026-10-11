package com.jbsmartbulb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.jbsmartbulb.protocol.FadePreset
import com.jbsmartbulb.protocol.LampColors
import com.jbsmartbulb.protocol.Rgb
import kotlin.math.roundToInt

/** Colour list + fade time of the looping colour fade. Every edit goes straight to [onChange]. */
@Composable
internal fun FadeEditor(preset: FadePreset, onChange: (FadePreset) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var fadeText by remember(preset.fadeMs) { mutableStateOf(preset.fadeMs.toString()) }

    Text("Colours", style = MaterialTheme.typography.titleMedium)
    preset.colors.forEachIndexed { index, color ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp)
                    .background(color.toColor(), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { editing = index }
                    .semantics { contentDescription = "Colour ${index + 1}, ${color.hex}" },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Colour ${index + 1}", style = MaterialTheme.typography.bodyLarge)
                Text(color.hex, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { editing = index }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit colour ${index + 1}") }
            IconButton(
                onClick = { onChange(preset.copy(colors = preset.colors.filterIndexed { i, _ -> i != index })) },
                enabled = preset.colors.size > 1,
            ) { Icon(Icons.Rounded.Delete, contentDescription = "Remove colour ${index + 1}") }
        }
    }
    var confirmDefault by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            // A new row starts a third of the way round the hue circle from the last colour.
            val (r, g, b) = LampColors.hueToRgb(hueOf(preset.colors.last()) + 120f)
            onChange(preset.copy(colors = preset.colors + Rgb(r, g, b)))
            editing = preset.colors.size
        }) {
            Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add colour")
        }
        OutlinedButton(onClick = {
            if (preset == FadePreset.SPECTRUM) return@OutlinedButton
            confirmDefault = true
        }) {
            Icon(Icons.Rounded.Restore, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Load default")
        }
    }
    if (confirmDefault) {
        AlertDialog(
            onDismissRequest = { confirmDefault = false },
            title = { Text("Load the default fade?") },
            text = { Text("Your colours are replaced by the full colour spectrum, fading every ${FadePreset.DEFAULT_FADE_MS} ms.") },
            confirmButton = {
                TextButton(onClick = { confirmDefault = false; onChange(FadePreset.SPECTRUM) }) { Text("Load default") }
            },
            dismissButton = { TextButton(onClick = { confirmDefault = false }) { Text("Cancel") } },
        )
    }

    val fadeMs = fadeText.toIntOrNull()?.takeIf { it > 0 }
    OutlinedTextField(
        value = fadeText,
        onValueChange = { text ->
            fadeText = text.filter(Char::isDigit).take(7)
            fadeText.toIntOrNull()?.takeIf { it > 0 }?.let { onChange(preset.copy(fadeMs = it)) }
        },
        label = { Text("Fade time") },
        suffix = { Text("ms") },
        singleLine = true,
        isError = fadeMs == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text(
                when {
                    fadeMs == null -> "Enter a time in milliseconds"
                    fadeMs < 300 -> "The bulb takes about 12 colours a second, so very short fades look stepped"
                    else -> "Time to fade from one colour to the next (default ${FadePreset.DEFAULT_FADE_MS} ms)"
                },
            )
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    editing?.let { index ->
        ColorDialog(
            title = "Colour ${index + 1}",
            initial = preset.colors.getOrElse(index) { preset.colors.last() },
            onDismiss = { editing = null },
            onDone = { picked ->
                editing = null
                onChange(preset.copy(colors = preset.colors.mapIndexed { i, c -> if (i == index) picked else c }))
            },
        )
    }
}

/** Colour wheel (skydoves colorpicker-compose) and a #RRGGBB field, kept in sync both ways. */
@Composable
private fun ColorDialog(title: String, initial: Rgb, onDismiss: () -> Unit, onDone: (Rgb) -> Unit) {
    val controller = rememberColorPickerController()
    var color by remember { mutableStateOf(initial) }
    var hexText by remember { mutableStateOf(initial.hex) }
    val parsed = Rgb.parse(hexText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f),
                    controller = controller,
                    initialColor = initial.toColor(),
                    onColorChanged = { envelope ->
                        if (envelope.fromUser) {
                            val c = envelope.color
                            color = Rgb((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
                            hexText = color.hex
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp)
                            .background(color.toColor(), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text.take(7)
                            Rgb.parse(hexText)?.let {
                                color = it
                                controller.selectByColor(it.toColor(), fromUser = false)
                            }
                        },
                        label = { Text("RGB code") },
                        placeholder = { Text("#FF6A00") },
                        singleLine = true,
                        isError = parsed == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(color) }, enabled = parsed != null) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Rgb.toColor() = Color(red, green, blue)

private fun hueOf(c: Rgb): Float {
    val max = maxOf(c.red, c.green, c.blue)
    val delta = (max - minOf(c.red, c.green, c.blue)).toFloat()
    if (delta == 0f) return 0f
    val hue = when (max) {
        c.red -> 60f * (((c.green - c.blue) / delta) % 6f)
        c.green -> 60f * ((c.blue - c.red) / delta + 2f)
        else -> 60f * ((c.red - c.green) / delta + 4f)
    }
    return (hue + 360f) % 360f
}
