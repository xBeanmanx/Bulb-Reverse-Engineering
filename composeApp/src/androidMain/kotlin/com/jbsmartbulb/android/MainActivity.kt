package com.jbsmartbulb.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jbsmartbulb.ui.PlatformHost
import com.jbsmartbulb.ui.SmartBulbApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val app get() = application as SmartBulbApplication
    private val granted = MutableStateFlow(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    private val host = object : PlatformHost {
        override val permissionsGranted: StateFlow<Boolean> = granted
        override fun requestPermissions() = permissionLauncher.launch(REQUIRED_PERMISSIONS)
        override val openBluetoothSettings: (() -> Unit) = {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshPermissions()
        if (!granted.value && savedInstanceState == null) host.requestPermissions()
        setContent { SmartBulbApp(app.controller, app.platform, host) }
    }


    override fun onStart() {
        super.onStart()
        refreshPermissions()
        app.controller.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        app.controller.setForeground(false)
    }

    private fun refreshPermissions() {
        granted.value = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
