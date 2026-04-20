package com.vaish.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = context.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            val isServiceEnabled = sharedPrefs.getBoolean("ServiceEnabled", false)
            
            if (isServiceEnabled) {
                val serviceIntent = Intent(context, AppLockService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
