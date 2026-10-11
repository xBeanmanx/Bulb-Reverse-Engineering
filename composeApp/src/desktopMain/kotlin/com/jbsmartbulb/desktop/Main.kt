package com.jbsmartbulb.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.transport.DemoBulbPlatform
import com.jbsmartbulb.transport.SerialBulbPlatform
import com.jbsmartbulb.ui.PlatformHost
import com.jbsmartbulb.ui.SmartBulbApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun main() {
    val platform = DemoBulbPlatform(SerialBulbPlatform())
    val controller = BulbController(platform, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    val host = object : PlatformHost {
        override val permissionsGranted: StateFlow<Boolean> = MutableStateFlow(true)
        override fun requestPermissions() = Unit
        override val openBluetoothSettings: (() -> Unit)? = null
    }
    application {
        Window(
            onCloseRequest = { controller.disconnect(); exitApplication() },
            title = "Smart Bulb app",
            state = rememberWindowState(width = 900.dp, height = 760.dp),
        ) {
            SmartBulbApp(controller, platform, host)
        }
    }
}
