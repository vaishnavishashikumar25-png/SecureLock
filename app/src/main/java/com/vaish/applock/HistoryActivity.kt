package com.vaish.applock

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.utils.ColorTemplate
import com.vaish.applock.databinding.ActivityHistoryBinding
import com.vaish.applock.databinding.ItemIntruderBinding
import com.vaish.applock.databinding.ItemUsageLogBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvIntruders.layoutManager = LinearLayoutManager(this)
        
        checkUsageStatsPermission()
        refreshList()
    }

    private fun checkUsageStatsPermission() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            Toast.makeText(this, "Please grant Usage Access for analytics", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun setupPieChart(logs: List<UsageLogEntry>) {
        val entries = ArrayList<PieEntry>()
        val appUsageMap = HashMap<String, Long>()

        logs.forEach { log ->
            appUsageMap[log.packageName] = (appUsageMap[log.packageName] ?: 0L) + log.duration
        }

        appUsageMap.forEach { (pkg, duration) ->
            val appName = try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg.split(".").last()
            }
            entries.add(PieEntry(duration.toFloat(), appName))
        }

        val dataSet = PieDataSet(entries, "App Usage")
        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 12f

        val data = PieData(dataSet)
        binding.pieChart.data = data
        binding.pieChart.description.isEnabled = false
        binding.pieChart.centerText = "Usage Distribution"
        binding.pieChart.animateY(1000)
        binding.pieChart.invalidate()
    }

    inner class IntruderAdapter(private val list: List<Intruder>, private val usageLogs: List<UsageLogEntry>) :
        RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemIntruderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val context = holder.itemView.context
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            holder.binding.tvTimestamp.text = sdf.format(Date(item.timestamp))

            if (item.latitude != null && item.longitude != null) {
                holder.binding.tvLocation.text = context.getString(R.string.fetching_address)
                holder.binding.tvLocation.visibility = View.VISIBLE
                
                // Reverse geocoding on a background thread
                Thread {
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            geocoder.getFromLocation(item.latitude, item.longitude, 1) { addresses ->
                                val text = if (!addresses.isNullOrEmpty()) {
                                    addresses[0].getAddressLine(0)
                                } else {
                                    String.format(Locale.getDefault(), context.getString(R.string.location_format), item.latitude, item.longitude)
                                }
                                (context as? HistoryActivity)?.runOnUiThread {
                                    holder.binding.tvLocation.text = text
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(item.latitude, item.longitude, 1)
                            val addressText = if (!addresses.isNullOrEmpty()) {
                                addresses[0].getAddressLine(0)
                            } else {
                                String.format(Locale.getDefault(), context.getString(R.string.location_format), item.latitude, item.longitude)
                            }
                            (context as? HistoryActivity)?.runOnUiThread {
                                holder.binding.tvLocation.text = addressText
                            }
                        }
                    } catch (e: Exception) {
                        val fallback = String.format(Locale.getDefault(), context.getString(R.string.location_format), item.latitude, item.longitude)
                        (context as? HistoryActivity)?.runOnUiThread {
                            holder.binding.tvLocation.text = fallback
                        }
                    }
                }.start()
            } else {
                holder.binding.tvLocation.visibility = View.GONE
            }
            
            val bitmap = BitmapFactory.decodeFile(item.imagePath)
            if (bitmap != null) {
                holder.binding.ivIntruder.setImageBitmap(bitmap)
                holder.binding.ivIntruder.visibility = View.VISIBLE
            } else {
                Log.e("HistoryActivity", "Failed to decode bitmap for: ${item.imagePath}")
                holder.binding.ivIntruder.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            holder.binding.btnDelete.setOnClickListener {
                File(item.imagePath).delete()
                this@HistoryActivity.refreshList()
            }

            // Filter usage logs for this intruder (within 30 seconds of capture)
            val filteredLogs = usageLogs.filter { 
                Math.abs(it.timestamp - item.timestamp) < 30000 
            }

            if (filteredLogs.isNotEmpty()) {
                holder.binding.tvUsageTitle.visibility = View.VISIBLE
                holder.binding.llUsageLogs.removeAllViews()
                filteredLogs.forEach { log ->
                    val logBinding = ItemUsageLogBinding.inflate(LayoutInflater.from(holder.itemView.context), holder.binding.llUsageLogs, false)
                    val appName = try {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(log.packageName, 0)).toString()
                    } catch (e: Exception) {
                        log.packageName.split(".").last()
                    }
                    logBinding.tvAppName.text = appName
                    logBinding.tvDuration.text = context.getString(R.string.duration_seconds, log.duration)
                    holder.binding.llUsageLogs.addView(logBinding.root)
                }
            } else {
                holder.binding.tvUsageTitle.visibility = View.GONE
                holder.binding.llUsageLogs.removeAllViews()
            }
        }

        override fun getItemCount() = list.size

        inner class ViewHolder(val binding: ItemIntruderBinding) : RecyclerView.ViewHolder(binding.root)
    }

    fun refreshList() {
        val files = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        Log.d("HistoryActivity", "Found ${files.size} intruder files")

        val intruderList = files.map { file ->
            val name = file.nameWithoutExtension // intruder_TIMESTAMP_loc_LAT_LON
            val parts = name.split("_")
            var timestamp = file.lastModified()
            var lat: Double? = null
            var lon: Double? = null
            
            if (parts.size >= 2) {
                timestamp = parts[1].toLongOrNull() ?: file.lastModified()
            }
            
            if (parts.contains("loc")) {
                val locIndex = parts.indexOf("loc")
                if (parts.size > locIndex + 2) {
                    lat = parts[locIndex + 1].toDoubleOrNull()
                    lon = parts[locIndex + 2].toDoubleOrNull()
                }
            }
            
            Intruder(file.absolutePath, timestamp, lat, lon)
        }

        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val logStrings = sharedPrefs.getStringSet("IntruderUsageLogs", emptySet()) ?: emptySet()
        
        Log.d("HistoryActivity", "Found ${logStrings.size} usage logs")

        val usageLogs = logStrings.mapNotNull { log ->
            val parts = log.split(" | ")
            if (parts.size == 3) {
                UsageLogEntry(parts[0], parts[1].toLong(), parts[2].toLong())
            } else {
                Log.e("HistoryActivity", "Invalid log format: $log")
                null
            }
        }.sortedByDescending { it.timestamp }

        // Populate Top Usage Logs
        binding.llAllUsageLogs.removeAllViews()
        if (usageLogs.isEmpty()) {
            binding.cardUsageLogs.visibility = View.GONE
            binding.tvUsageLogsTitle.visibility = View.GONE
            Log.d("HistoryActivity", "No usage logs to display")
        } else {
            binding.cardUsageLogs.visibility = View.VISIBLE
            binding.tvUsageLogsTitle.visibility = View.VISIBLE
            usageLogs.take(10).forEach { log -> // Show last 10 logs
                val logBinding = ItemUsageLogBinding.inflate(layoutInflater, binding.llAllUsageLogs, false)
                val appName = try {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(log.packageName, 0)).toString()
                } catch (e: Exception) {
                    log.packageName.split(".").last()
                }
                logBinding.tvAppName.text = appName
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                logBinding.tvDuration.text = getString(R.string.duration_at_time, log.duration, timeStr)
                binding.llAllUsageLogs.addView(logBinding.root)
            }
        }

        binding.rvIntruders.adapter = IntruderAdapter(intruderList, usageLogs)
        setupPieChart(usageLogs)
    }

    data class UsageLogEntry(val packageName: String, val timestamp: Long, val duration: Long)
}
