package com.jbsmartbulb.android

import android.app.Application
import android.content.pm.ApplicationInfo
import com.jbsmartbulb.BulbController
import com.jbsmartbulb.transport.AndroidBulbPlatform
import com.jbsmartbulb.transport.BulbPlatform
import com.jbsmartbulb.transport.DemoBulbPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the connection for the whole process, so it survives rotation and activity restarts. */
class SmartBulbApplication : Application() {
    lateinit var platform: BulbPlatform
        private set
    lateinit var controller: BulbController
        private set

    override fun onCreate() {
        super.onCreate()
        val bluetooth = AndroidBulbPlatform(this)
        // Debug builds also list a simulated bulb, so the UI can be tried without hardware.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        platform = if (debuggable) DemoBulbPlatform(bluetooth) else bluetooth
        controller = BulbController(platform, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}
