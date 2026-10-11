package com.jbsmartbulb.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme

/** Warm bulb amber; the M3 tonal scheme is generated from it when dynamic colour is unavailable. */
private val SeedColor = Color(0xFFFFB300)

/** Android 12+ wallpaper colours; null where the platform has none. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

@Composable
fun SmartBulbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = platformDynamicColorScheme(dark)
        ?: rememberDynamicColorScheme(seedColor = SeedColor, isDark = dark)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
