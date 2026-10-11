package com.jbsmartbulb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.WbIncandescent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.protocol.FadePreset
import com.jbsmartbulb.protocol.LampColors
import com.jbsmartbulb.protocol.LampState
import kotlin.math.roundToInt

/** Track colours are the light itself (temperature / hue), not UI theme colours. */
private val TemperatureTrack = Brush.horizontalGradient(
    (0..10).map { rgb(LampColors.kelvinToRgb(LampColors.kelvin(it / 10f))) },
)
private val HueTrack = Brush.horizontalGradient((0..6).map { rgb(LampColors.hueToRgb(it * 60f)) })

private const val MIN_BRIGHTNESS = 0.02f

private enum class LampMode(val label: String) { White("White"), Colour("Colour"), Fade("Fade") }

@Composable
fun LampScreen(controller: BulbController, fadeStore: FadeStore) {
    val lamp by controller.lamp.collectAsState()
    val fading by controller.fade.collectAsState()
    val initial = remember { LampColors.describe(lamp) }
    var mode by remember {
        mutableStateOf(
            when {
                controller.fade.value != null -> LampMode.Fade
                initial.isWhite -> LampMode.White
                else -> LampMode.Colour
            },
        )
    }
    var temperature by remember { mutableFloatStateOf(initial.temperature) }
    var hue by remember { mutableFloatStateOf(initial.hue) }
    var brightness by remember { mutableFloatStateOf(initial.brightness.coerceAtLeast(MIN_BRIGHTNESS)) }
    var fade by remember { mutableStateOf(controller.fade.value ?: fadeStore.load()) }
    var lastSent by remember { mutableStateOf<LampState?>(null) }

    // A state the bulb reported (e.g. the reply to the lamp query on connect) moves the controls.
    LaunchedEffect(lamp) {
        if (lamp != lastSent && lamp.isOn && controller.fade.value == null) {
            val shown = LampColors.describe(lamp)
            mode = if (shown.isWhite) LampMode.White else LampMode.Colour
            temperature = shown.temperature
            hue = shown.hue
            brightness = shown.brightness.coerceAtLeast(MIN_BRIGHTNESS)
        }
    }

    fun sendCurrent() {
        when (mode) {
            LampMode.Fade -> controller.startFade(fade, brightness)
            LampMode.White, LampMode.Colour -> {
                val state = if (mode == LampMode.White) LampColors.white(temperature, brightness) else LampColors.colour(hue, brightness)
                lastSent = state
                controller.setLamp(state)
            }
        }
    }

    fun updateFade(preset: FadePreset) {
        fade = preset
        fadeStore.save(preset)
        if (mode == LampMode.Fade && (fading != null || lamp.isOn)) controller.startFade(preset, brightness)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PowerCard(lamp, fading != null, onToggle = { on ->
            if (on) {
                sendCurrent()
            } else {
                lastSent = LampState.OFF
                controller.setLamp(LampState.OFF)
            }
        })

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LampMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { mode = item; sendCurrent() },
                    shape = SegmentedButtonDefaults.itemShape(index, LampMode.entries.size),
                    icon = {
                        SegmentedButtonDefaults.Icon(active = mode == item) {
                            Icon(
                                when (item) {
                                    LampMode.White -> Icons.Rounded.WbIncandescent
                                    LampMode.Colour -> Icons.Rounded.Palette
                                    LampMode.Fade -> Icons.Rounded.Gradient
                                },
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        }
                    },
                ) { Text(item.label) }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    LampMode.White -> {
                        LabelRow("Temperature", "${LampColors.kelvin(temperature)} K")
                        GradientSlider(temperature, TemperatureTrack, 0f..1f) { temperature = it; sendCurrent() }
                        Row {
                            Caption("Cold", Modifier.weight(1f))
                            Caption("Warm")
                        }
                    }
                    LampMode.Colour -> {
                        LabelRow("Colour", null)
                        GradientSlider(hue, HueTrack, 0f..360f) { hue = it; sendCurrent() }
                    }
                    LampMode.Fade -> FadeEditor(fade, onChange = ::updateFade)
                }
                LabelRow("Brightness", "${(brightness * 100).roundToInt()}%", Modifier.padding(top = 8.dp))
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it; sendCurrent() },
                    valueRange = MIN_BRIGHTNESS..1f,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    value: Float,
    track: Brush,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        track = {
            Box(
                Modifier.fillMaxWidth().height(16.dp)
                    .background(track, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        },
    )
}

@Composable
private fun LabelRow(title: String, value: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun PowerCard(lamp: LampState, fading: Boolean, onToggle: (Boolean) -> Unit) {
    val glow by animateColorAsState(
        when {
            !lamp.isOn -> MaterialTheme.colorScheme.surfaceContainerHighest
            lamp.red + lamp.green + lamp.blue == 0 -> Color(0xFFFFF4E0)
            else -> rgb(Triple(lamp.red, lamp.green, lamp.blue))
        },
    )
    val shown = LampColors.describe(lamp)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier.size(72.dp).background(glow, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(if (lamp.isOn) "On" else "Off", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        !lamp.isOn -> "Tap the power button to switch on"
                        fading -> "Fading"
                        shown.isWhite -> "White · ${(shown.brightness * 100).roundToInt()}%"
                        else -> "Colour · ${(shown.brightness * 100).roundToInt()}%"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconToggleButton(
                checked = lamp.isOn,
                onCheckedChange = onToggle,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (lamp.isOn) "Turn off" else "Turn on")
            }
        }
    }
}

private fun rgb(c: Triple<Int, Int, Int>) = Color(c.first, c.second, c.third)
