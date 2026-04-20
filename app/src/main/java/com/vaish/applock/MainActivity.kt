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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: SwitchMaterial
    private lateinit var ivStatus: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        updateStatusUI()
    }

    private fun setupUI() {
        switchService = findViewById(R.id.switchService)
        ivStatus = findViewById(R.id.ivStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)

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

        findViewById<MaterialCardView>(R.id.btnViewLogs).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.btnSelectApps).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.btnSetPin).setOnClickListener {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }
    }

    private fun updateStatusUI() {
        val isRunning = isServiceRunning(AppLockService::class.java)
        switchService.isChecked = isRunning
        
        if (isRunning) {
            ivStatus.setImageResource(android.R.drawable.ic_lock_idle_lock)
            ivStatus.setColorFilter(getColor(android.R.color.holo_green_dark))
            tvStatusTitle.text = getString(R.string.status_active)
            tvStatusDesc.text = getString(R.string.status_desc_active)
        } else {
            ivStatus.setImageResource(android.R.drawable.ic_lock_lock)
            ivStatus.setColorFilter(getColor(android.R.color.holo_red_dark))
            tvStatusTitle.text = getString(R.string.status_disabled)
            tvStatusDesc.text = getString(R.string.status_desc_disabled)
        }
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

        return usageStatsGranted && overlayGranted
    }

    private fun requestPermissions() {
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

    private fun startAppLockService() {
        val intent = Intent(this, AppLockService::class.java)
        startForegroundService(intent)
    }

    private fun stopAppLockService() {
        stopService(Intent(this, AppLockService::class.java))
        Toast.makeText(this, "AppLock Service Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }
}
