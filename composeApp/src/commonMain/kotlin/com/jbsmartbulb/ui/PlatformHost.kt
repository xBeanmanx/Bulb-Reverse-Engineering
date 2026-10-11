package com.jbsmartbulb.ui

import kotlinx.coroutines.flow.StateFlow

/** The few things the shared UI needs from the host platform. */
interface PlatformHost {
    /** Runtime Bluetooth permissions (Android 12+: CONNECT + SCAN; Android 6–11: location). */
    val permissionsGranted: StateFlow<Boolean>

    fun requestPermissions()

    /** Null when the platform has no Bluetooth settings screen to open. */
    val openBluetoothSettings: (() -> Unit)?
}
