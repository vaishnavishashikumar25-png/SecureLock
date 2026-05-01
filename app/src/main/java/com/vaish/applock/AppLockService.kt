package com.vaish.applock

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class AppLockService : Service() {

    private var timer: Timer? = null
    private val lockedPackages = mutableSetOf<String>()
    private var currentTrackingPackage: String? = null
    private var trackingStartTime: Long = 0
    private var lastTriggerTime: Long = 0
    private var lastProtectedTick: Long = 0
    private val TRIGGER_COOLDOWN = 10000L // 10 seconds cooldown between stealth checks
    private var screenUnlockReceiver: BroadcastReceiver? = null
    private lateinit var behaviorModel: BehaviorModel
    private lateinit var riskEngine: RiskEngine


    companion object {
        var lastUnlockedApp: String? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        updateLockedPackages()
        createNotificationChannel()
        registerScreenUnlockReceiver()
        behaviorModel = BehaviorModel(this)
        riskEngine = RiskEngine(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, createNotification())
        }

        startMonitoring()
    }


    private fun registerScreenUnlockReceiver() {
        screenUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        Log.d("AppLockService", "Screen unlocked. Resuming monitoring.")
                        startMonitoring()
                        triggerStealthPhoto()
                        cleanupOldLogs() // Run cleanup once per unlock
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("AppLockService", "Screen off. Pausing monitoring.")
                        stopMonitoring()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenUnlockReceiver, filter)
    }

    private fun stopMonitoring() {
        timer?.cancel()
        timer = null
        saveCurrentTracking()
        currentTrackingPackage = null
    }

    private fun cleanupOldLogs() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        
        // Cleanup Photos
        val dir = getExternalFilesDir(null)
        dir?.listFiles { file -> file.name.startsWith("intruder_") }?.forEach { file ->
            if (file.lastModified() < sevenDaysAgo) {
                file.delete()
            }
        }

        // Cleanup Usage Logs
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val logStrings = sharedPrefs.getStringSet("IntruderUsageLogs", emptySet()) ?: emptySet()
        val newLogs = logStrings.filter { log ->
            val parts = log.split(" | ")
            parts.size == 3 && parts[1].toLong() > sevenDaysAgo
        }.toSet()
        sharedPrefs.edit().putStringSet("IntruderUsageLogs", newLogs).apply()
    }

    private fun updateLockedPackages() {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val packages = sharedPrefs.getStringSet("LockedPackages", emptySet()) ?: emptySet()
        lockedPackages.clear()
        lockedPackages.addAll(packages)
    }

    private fun startMonitoring() {
        timer?.cancel()
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val currentApp = getForegroundApp()
                if (currentApp == null) {
                    return
                }
                
                Log.d("AppLockService", "Monitoring foreground app: $currentApp")

                // If our own app is in foreground, do nothing
                if (currentApp == packageName) {
                    return
                }



                trackUsageIfNeeded(currentApp)

                // Update "Time Protected" metric every second
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProtectedTick >= 1000) {
                    lastProtectedTick = currentTime
                    val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                    val totalProtectedSeconds = sharedPrefs.getLong("TotalProtectedSeconds", 0L)
                    sharedPrefs.edit().putLong("TotalProtectedSeconds", totalProtectedSeconds + 1).apply()
                }

                // Periodic check for locked packages update
                if (System.currentTimeMillis() % 60000 < 1000) {
                    updateLockedPackages()
                }
            }
        }, 0, 1000) // 1 second intervals for general monitoring
    }

    private fun trackUsageIfNeeded(packageName: String) {
        if (packageName == this.packageName) return

        val currentTime = System.currentTimeMillis()
        val ignorePackages = listOf(
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.sec.android.app.launcher"
        )

        if (packageName != currentTrackingPackage) {
            saveCurrentTracking()
            
            val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            val isBehaviorEnabled = sharedPrefs.getBoolean("BehaviorLock", false)

            // Trigger check with COOLDOWN and DELAY
            if (!ignorePackages.contains(packageName)) {
                val riskLevel = riskEngine.getRiskLevel(packageName)
                val isLocked = lockedPackages.contains(packageName)

                if (isLocked || riskLevel != RiskEngine.RiskLevel.LOW || (currentTime - lastTriggerTime > TRIGGER_COOLDOWN)) {
                    Log.d("AppLockService", "Check triggered. Reason: Locked=$isLocked, Risk=$riskLevel, Time=${currentTime - lastTriggerTime > TRIGGER_COOLDOWN}")
                    lastTriggerTime = currentTime
                    
                    // Delay the stealth check by 2 seconds so the app opens instantly
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({
                        if (getForegroundApp() == packageName) {
                            if (isLocked || riskLevel != RiskEngine.RiskLevel.LOW) {
                                showLockScreen(packageName, riskLevel)
                            } else {
                                triggerStealthPhoto()
                            }
                        }
                    }, 2000)
                }
            }
            
            currentTrackingPackage = packageName
            trackingStartTime = currentTime
        }
    }

    private fun triggerStealthPhoto() {
        val intent = Intent(this, LockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("MODE", "STEALTH")
        startActivity(intent)
    }

    private fun saveCurrentTracking() {
        if (currentTrackingPackage != null && currentTrackingPackage != packageName) {
            val duration = (System.currentTimeMillis() - trackingStartTime) / 1000 // in seconds
            if (duration > 0) { // Log all durations
                val logEntry = "$currentTrackingPackage | ${System.currentTimeMillis()} | $duration"
                val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                
                // Only learn if the owner was verified in this session
                val isIntruder = sharedPrefs.getBoolean("IsIntruderSession", false)
                if (!isIntruder) {
                    behaviorModel.learn(currentTrackingPackage!!, duration)
                }

                val existingLogs = sharedPrefs.getStringSet("IntruderUsageLogs", mutableSetOf()) ?: mutableSetOf()
                val newLogs = HashSet(existingLogs)
                newLogs.add(logEntry)
                sharedPrefs.edit().putStringSet("IntruderUsageLogs", newLogs).apply()
                Log.d("AppLockService", "Logged usage: $logEntry")
            }
        }
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        
        // Use a slightly larger window for events to ensure we don't miss transitions
        val events = usageStatsManager.queryEvents(time - 3000, time)
        val event = android.app.usage.UsageEvents.Event()
        var lastPackage: String? = null
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // Use both MOVE_TO_FOREGROUND and ACTIVITY_RESUMED for better compatibility
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND || 
                event.eventType == 1) { // 1 is ACTIVITY_RESUMED
                lastPackage = event.packageName
            }
        }
        
        if (lastPackage != null) return lastPackage

        // Fallback to queryUsageStats
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        if (!stats.isNullOrEmpty()) {
            // Filter out system events that aren't real apps if possible, or just get the latest
            return stats.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        return null
    }

    private fun showLockScreen(targetPackage: String, riskLevel: RiskEngine.RiskLevel = RiskEngine.RiskLevel.LOW) {
        val intent = Intent(this, LockActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("TARGET_PACKAGE", targetPackage)
        intent.putExtra("RISK_LEVEL", riskLevel.name)
        startActivity(intent)
    }



    private fun createNotificationChannel() {
        val channel = NotificationChannel("app_lock_channel", "AppLock Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, HistoryActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "app_lock_channel")
            .setContentTitle("AppLock is Running")
            .setContentText("Monitoring background activity")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_recent_history, "View Logs", pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        screenUnlockReceiver?.let { unregisterReceiver(it) }
    }
}
