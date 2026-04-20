package com.vaish.applock

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.*

class AppLockService : Service() {

    private var timer: Timer? = null
    private val lockedPackages = mutableSetOf<String>()

    companion object {
        var lastUnlockedApp: String? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        updateLockedPackages()
        createNotificationChannel()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }

        startMonitoring()
    }

    private fun updateLockedPackages() {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val packages = sharedPrefs.getStringSet("LockedPackages", emptySet()) ?: emptySet()
        lockedPackages.clear()
        lockedPackages.addAll(packages)
    }

    private fun startMonitoring() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                updateLockedPackages() // Refresh list occasionally
                val currentApp = getForegroundApp() ?: return
                
                // If the current app is the one we just unlocked, don't show lock screen
                if (currentApp == lastUnlockedApp) {
                    return
                }

                // If the user switched to a different app (not our app), reset the last unlocked app
                if (lastUnlockedApp != null && currentApp != lastUnlockedApp && !lockedPackages.contains(currentApp) && currentApp != packageName) {
                    lastUnlockedApp = null
                }

                if (lockedPackages.contains(currentApp) && currentApp != packageName) {
                    showLockScreen(currentApp)
                }
            }
        }, 0, 1000)
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        
        if (stats != null && stats.isNotEmpty()) {
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            // Filter out our own app to find what's underneath
            for (stat in sortedStats) {
                if (stat.packageName != packageName) {
                    return stat.packageName
                }
            }
            return sortedStats[0].packageName
        }
        return null
    }

    private fun showLockScreen(targetPackage: String) {
        val intent = Intent(this, LockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("TARGET_PACKAGE", targetPackage)
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("app_lock_channel", "AppLock Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "app_lock_channel")
            .setContentTitle("AppLock is Running")
            .setContentText("Monitoring protected apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
