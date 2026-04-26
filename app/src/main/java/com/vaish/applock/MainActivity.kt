package com.vaish.applock

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: MaterialSwitch
    private lateinit var ivStatus: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var statusPulse: android.view.View

    private var isAuthenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // App-level PIN protection
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val savedPin = sharedPrefs.getString("SecurityPin", null)
        
        if (savedPin != null && !isAuthenticated) {
            val intent = Intent(this, LockActivity::class.java)
            intent.putExtra("MODE", "APP_UNLOCK")
            startActivityForResult(intent, 999)
        }

        setContentView(R.layout.activity_main)
        setupUI()
        updateStatusUI()
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
    }

    private fun updateStatusUI() {
        val isRunning = isServiceRunning(AppLockService::class.java)
        switchService.isChecked = isRunning
        
        if (isRunning) {
            ivStatus.setImageResource(android.R.drawable.ic_lock_idle_lock)
            ivStatus.setColorFilter(getColor(R.color.primary))
            tvStatusTitle.text = "System Protected"
            tvStatusDesc.text = "Monitoring background activity"
            startPulseAnimation()
        } else {
            ivStatus.setImageResource(android.R.drawable.ic_lock_lock)
            ivStatus.setColorFilter(getColor(R.color.text_secondary))
            tvStatusTitle.text = "Protection Disabled"
            tvStatusDesc.text = "Tap to enable security"
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation() {
        statusPulse.visibility = android.view.View.VISIBLE
        val scaleX = android.view.animation.ScaleAnimation(1f, 1.5f, 1f, 1.5f, 
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, 
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f)
        scaleX.duration = 1500
        scaleX.repeatCount = android.view.animation.Animation.INFINITE
        scaleX.repeatMode = android.view.animation.Animation.REVERSE
        
        val alpha = android.view.animation.AlphaAnimation(0.2f, 0f)
        alpha.duration = 1500
        alpha.repeatCount = android.view.animation.Animation.INFINITE
        alpha.repeatMode = android.view.animation.Animation.REVERSE

        val animSet = android.view.animation.AnimationSet(true)
        animSet.addAnimation(scaleX)
        animSet.addAnimation(alpha)
        statusPulse.startAnimation(animSet)
    }

    private fun stopPulseAnimation() {
        statusPulse.clearAnimation()
        statusPulse.visibility = android.view.View.GONE
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

        return usageStatsGranted && overlayGranted && cameraGranted
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != 
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 100)
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
    }
}
