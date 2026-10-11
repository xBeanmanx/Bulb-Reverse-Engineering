package com.jbsmartbulb.ui

import com.jbsmartbulb.protocol.FadePreset
import com.jbsmartbulb.protocol.Rgb
import com.russhwolf.settings.Settings

/** Keeps the user's fade colours and timing between launches. */
class FadeStore(private val settings: Settings = Settings()) {
    fun load(): FadePreset {
        val colors = settings.getStringOrNull(KEY_COLORS)?.split(",")?.mapNotNull(Rgb::parse).orEmpty()
        val fadeMs = settings.getInt(KEY_FADE_MS, FadePreset.DEFAULT_FADE_MS).takeIf { it > 0 } ?: FadePreset.DEFAULT_FADE_MS
        return FadePreset(colors.ifEmpty { listOf(DEFAULT_COLOR) }, fadeMs)
    }

    fun save(preset: FadePreset) {
        settings.putString(KEY_COLORS, preset.colors.joinToString(",") { it.hex })
        settings.putInt(KEY_FADE_MS, preset.fadeMs)
    }

    private companion object {
        const val KEY_COLORS = "fade.colors"
        const val KEY_FADE_MS = "fade.ms"
        val DEFAULT_COLOR = Rgb(0xFF, 0x6A, 0x00)
    }
}
