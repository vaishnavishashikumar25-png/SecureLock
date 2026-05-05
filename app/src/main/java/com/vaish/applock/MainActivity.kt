package com.vaish.applock

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: MaterialSwitch
    private lateinit var ivStatus: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var statusPulse: View
    private lateinit var usageChart: BarChart
    private lateinit var securityChart: LineChart
    


    private lateinit var riskProgress: CircularProgressIndicator
    private lateinit var tvRiskScore: TextView
    private lateinit var tvRiskStatus: TextView
    


    private var isAuthenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // App-level PIN protection
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        
        // Apply Dark Mode Preference
        val isDarkMode = sharedPrefs.getBoolean("DarkMode", false)
        if (isDarkMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        val savedPin = sharedPrefs.getString("SecurityPin", null)
        
        if (savedPin != null && !isAuthenticated) {
            val intent = Intent(this, LockActivity::class.java)
            intent.putExtra("MODE", "APP_UNLOCK")
            startActivityForResult(intent, 999)
        } else if (savedPin == null) {
            // First time or no PIN set - force PIN setup for security
            startActivity(Intent(this, PinSetupActivity::class.java))
            Toast.makeText(this, "Please set a security PIN first", Toast.LENGTH_LONG).show()
        }

        setContentView(R.layout.activity_main)
        setupUI()
        setupAnalytics()
        updateStatusUI()
        scanNetworkSecurity()
    }

    private fun scanNetworkSecurity() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // For API 31+, we ideally use NetworkCallback or other methods, 
                // but connectionInfo is still usable with caveats or via specific APIs.
                // Keeping it simple for the logic flow.
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo
            } else {
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo
            }
            
            var score = 0
            val details = StringBuilder()

            if (wifiInfo != null) {
                // 1. Encryption check (simplified for demo)
                // In a real app, you'd check ScanResult for capabilities
                val isEncrypted = true // placeholder
                if (isEncrypted) {
                    score += 50
                    details.append(getString(R.string.encrypted)).append(" • ")
                } else {
                    details.append(getString(R.string.open_network)).append(" • ")
                }

                // 2. Signal Strength
                val rssi = wifiInfo.rssi
                val level = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wifiManager.calculateSignalLevel(rssi) // Defaults to 5 levels or max levels
                    // To get 100 levels manually if needed:
                    ((rssi + 100).coerceIn(0, 100))
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(rssi, 100)
                }
                
                score += (level / 2) // Max 50 points for signal
                details.append(getString(R.string.signal_strength, level))

                // 3. SSID Check (Hidden or Common)
                val ssid = wifiInfo.ssid
                if (ssid.contains("Free", ignoreCase = true) || ssid.contains("Public", ignoreCase = true)) {
                    score -= 20
                }

                score = score.coerceIn(0, 100)
                updateRiskUI(score, details.toString())
            }
        } else {
            riskProgress.progress = 0
            tvRiskScore.text = getString(R.string.no_wifi)
            tvRiskStatus.text = getString(R.string.connect_wifi_scan)
        }
    }

    private fun updateRiskUI(score: Int, status: String) {
        riskProgress.setProgress(score, true)
        tvRiskScore.text = getString(R.string.risk_score, score)
        tvRiskStatus.text = status

        val color = when {
            score >= 80 -> getColor(R.color.secondary) // Good
            score >= 50 -> getColor(R.color.amber_500) // Warning
            else -> getColor(R.color.error) // Danger
        }
        riskProgress.setIndicatorColor(color)
        tvRiskScore.setTextColor(color)
    }

    private fun setupAnalytics() {
        usageChart = findViewById(R.id.usageChart)
        securityChart = findViewById(R.id.securityChart)

        updateAnalyticsData()
    }

    private fun updateAnalyticsData() {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val logStrings = sharedPrefs.getStringSet("IntruderUsageLogs", emptySet()) ?: emptySet()
        
        val usageLogs = logStrings.mapNotNull { log ->
            val parts = log.split(" | ")
            if (parts.size == 3) {
                UsageLogEntry(parts[0], parts[1].toLong(), parts[2].toLong())
            } else null
        }

        val intruderFiles = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        } ?: emptyArray()

        setupUsageChart(usageLogs)
        setupSecurityChart(intruderFiles)
        
        // Calculate real counters
        val totalProtectedSeconds = sharedPrefs.getLong("TotalProtectedSeconds", 0L)
        val hours = totalProtectedSeconds / 3600
        val minutes = (totalProtectedSeconds % 3600) / 60
        findViewById<TextView>(R.id.tvTimeProtected).text = String.format(Locale.getDefault(), "%dh %dm", hours, minutes)

        val lockedPackages = sharedPrefs.getStringSet("LockedPackages", emptySet())?.size ?: 0
        findViewById<TextView>(R.id.tvTotalLocks).text = lockedPackages.toString()
    }

    data class UsageLogEntry(val packageName: String, val timestamp: Long, val duration: Long)

    private fun setupUsageChart(logs: List<UsageLogEntry>) {
        val entries = ArrayList<BarEntry>()
        val calendar = Calendar.getInstance()
        
        // Group usage by day for the last 7 days
        val usageByDay = mutableMapOf<Int, Long>()
        for (i in 0..6) {
            calendar.timeInMillis = System.currentTimeMillis()
            calendar.add(Calendar.DAY_OF_YEAR, -i)
            usageByDay[calendar.get(Calendar.DAY_OF_YEAR)] = 0L
        }

        logs.forEach { log ->
            calendar.timeInMillis = log.timestamp
            val day = calendar.get(Calendar.DAY_OF_YEAR)
            if (usageByDay.containsKey(day)) {
                usageByDay[day] = usageByDay[day]!! + log.duration
            }
        }

        // Sort by day and add to entries
        val sortedDays = usageByDay.keys.sorted()
        sortedDays.forEachIndexed { index, day ->
            entries.add(BarEntry(index.toFloat(), usageByDay[day]!!.toFloat() / 60f)) // in minutes
        }

        val dataSet = BarDataSet(entries, "Usage (min)")
        dataSet.color = getColor(R.color.primary)
        dataSet.setDrawValues(false)

        val barData = BarData(dataSet)
        usageChart.data = barData
        usageChart.description.isEnabled = false
        usageChart.axisRight.isEnabled = false
        usageChart.xAxis.setDrawGridLines(false)
        usageChart.animateY(1000)
        usageChart.invalidate()
    }

    private fun setupSecurityChart(files: Array<java.io.File>) {
        val entries = ArrayList<Entry>()
        val calendar = Calendar.getInstance()

        // Group intruder attempts by day for the last 7 days
        val attemptsByDay = mutableMapOf<Int, Int>()
        for (i in 0..6) {
            calendar.timeInMillis = System.currentTimeMillis()
            calendar.add(Calendar.DAY_OF_YEAR, -i)
            attemptsByDay[calendar.get(Calendar.DAY_OF_YEAR)] = 0
        }

        files.forEach { file ->
            calendar.timeInMillis = file.lastModified()
            val day = calendar.get(Calendar.DAY_OF_YEAR)
            if (attemptsByDay.containsKey(day)) {
                attemptsByDay[day] = attemptsByDay[day]!! + 1
            }
        }

        val sortedDays = attemptsByDay.keys.sorted()
        sortedDays.forEachIndexed { index, day ->
            entries.add(Entry(index.toFloat(), attemptsByDay[day]!!.toFloat()))
        }

        val dataSet = LineDataSet(entries, "Intruder Attempts")
        dataSet.color = getColor(R.color.error)
        dataSet.setCircleColor(getColor(R.color.error))
        dataSet.lineWidth = 3f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = getColor(R.color.accent_red)

        val lineData = LineData(dataSet)
        securityChart.data = lineData
        securityChart.description.isEnabled = false
        securityChart.axisRight.isEnabled = false
        securityChart.animateX(1000)
        securityChart.invalidate()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 999) {
            if (resultCode == RESULT_OK) {
                isAuthenticated = true
            } else {
                finish() // Close app if unlock failed
            }
        }
    }

    private fun setupUI() {
        switchService = findViewById(R.id.switchService)
        ivStatus = findViewById(R.id.ivStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)
        statusPulse = findViewById(R.id.statusPulse)

        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkPermissions()) {
                    startAppLockService()
                } else {
                    switchService.isChecked = false
                    requestPermissions()
                }
            } else {
                stopAppLockService()
            }
            updateStatusUI()
        }

        findViewById<MaterialCardView>(R.id.btnRegisterOwner).setOnClickListener {
            registerOwner()
        }


        findViewById<MaterialCardView>(R.id.btnSetPin).setOnClickListener {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.btnViewLogs).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.btnTestIntruder).setOnClickListener {
            val intent = Intent(this, LockActivity::class.java)
            intent.putExtra("MODE", "STEALTH")
            intent.putExtra("TEST_MODE", true)
            startActivity(intent)
            Toast.makeText(this, "Simulating intruder... Keep camera active", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialCardView>(R.id.btnTestIntruder).setOnClickListener {
            val intent = Intent(this, LockActivity::class.java)
            intent.putExtra("MODE", "STEALTH")
            intent.putExtra("TEST_MODE", true)
            startActivity(intent)
            Toast.makeText(this, "Simulating intruder... Keep camera active", Toast.LENGTH_SHORT).show()
        }



        findViewById<MaterialCardView>(R.id.btnTestStealth).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                Toast.makeText(this, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(this, LockActivity::class.java)
                intent.putExtra("MODE", "STEALTH")
                startActivity(intent)
                Toast.makeText(this, "Stealth check started in background...", Toast.LENGTH_SHORT).show()
            }
        }


        // Behavioral Lock Toggle
        val switchBehavior = findViewById<MaterialSwitch>(R.id.switchBehavior)
        switchBehavior.isChecked = sharedPrefs.getBoolean("BehaviorLock", false)
        switchBehavior.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("BehaviorLock", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "Behavioral Lock Enabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Dark Mode Toggle
        val switchDarkMode = findViewById<MaterialSwitch>(R.id.switchDarkMode)
        val isDarkMode = sharedPrefs.getBoolean("DarkMode", false)
        switchDarkMode.isChecked = isDarkMode
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("DarkMode", isChecked).apply()
            if (isChecked) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            }
        }


        riskProgress = findViewById(R.id.riskProgress)
        tvRiskScore = findViewById(R.id.tvRiskScore)
        tvRiskStatus = findViewById(R.id.tvRiskStatus)
    }

    private fun updateStatusUI() {
        val isRunning = isServiceRunning(AppLockService::class.java)
        switchService.isChecked = isRunning
        
        if (isRunning) {
            ivStatus.setImageResource(android.R.drawable.ic_lock_idle_lock)
            ivStatus.setColorFilter(getColor(R.color.primary))
            tvStatusTitle.text = getString(R.string.status_active)
            tvStatusTitle.setTextColor(getColor(R.color.secondary))
            tvStatusDesc.text = getString(R.string.status_desc_active)
            startPulseAnimation()
        } else {
            ivStatus.setImageResource(android.R.drawable.ic_lock_lock)
            ivStatus.setColorFilter(getColor(R.color.text_secondary))
            tvStatusTitle.text = getString(R.string.status_disabled)
            tvStatusTitle.setTextColor(getColor(R.color.error))
            tvStatusDesc.text = getString(R.string.status_desc_disabled)
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation() {
        statusPulse.visibility = View.VISIBLE
        val scaleX = ScaleAnimation(1f, 1.5f, 1f, 1.5f, 
            Animation.RELATIVE_TO_SELF, 0.5f, 
            Animation.RELATIVE_TO_SELF, 0.5f)
        scaleX.duration = 1500
        scaleX.repeatCount = Animation.INFINITE
        scaleX.repeatMode = Animation.REVERSE
        
        val alpha = AlphaAnimation(0.2f, 0f)
        alpha.duration = 1500
        alpha.repeatCount = Animation.INFINITE
        alpha.repeatMode = Animation.REVERSE

        val animSet = AnimationSet(true)
        animSet.addAnimation(scaleX)
        animSet.addAnimation(alpha)
        statusPulse.startAnimation(animSet)
    }

    private fun stopPulseAnimation() {
        statusPulse.clearAnimation()
        statusPulse.visibility = View.GONE
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun registerOwner() {
        val intent = Intent(this, LockActivity::class.java)
        intent.putExtra("MODE", "REGISTER")
        startActivity(intent)
    }

    private fun checkPermissions(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
            android.os.Process.myUid(), packageName)
        val usageStatsGranted = mode == AppOpsManager.MODE_ALLOWED

        val overlayGranted = Settings.canDrawOverlays(this)

        val cameraGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
        
        val locationGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED

        return usageStatsGranted && overlayGranted && cameraGranted && locationGranted
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != 
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != 
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Grant Overlay Permission", Toast.LENGTH_LONG).show()
        }

        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
            android.os.Process.myUid(), packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "Grant Usage Access", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (checkPermissions()) {
                    startAppLockService()
                    updateStatusUI()
                }
            } else {
                Toast.makeText(this, "Camera permission is required for face unlock", Toast.LENGTH_SHORT).show()
                switchService.isChecked = false
            }
        }
    }

    private fun startAppLockService() {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("ServiceEnabled", true).apply()
        val intent = Intent(this, AppLockService::class.java)
        startForegroundService(intent)
    }

    private fun stopAppLockService() {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("ServiceEnabled", false).apply()
        stopService(Intent(this, AppLockService::class.java))
        Toast.makeText(this, "AppLock Service Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
        updateAnalyticsData()
        scanNetworkSecurity()
    }
}
