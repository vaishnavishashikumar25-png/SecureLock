package com.vaish.applock

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        refreshList()
    }

    private fun setupPieChart(usageLogs: List<UsageLogEntry>) {
        if (usageLogs.isEmpty()) {
            binding.cardAnalytics.visibility = View.GONE
            return
        }
        binding.cardAnalytics.visibility = View.VISIBLE

        val appCountMap = HashMap<String, Float>()
        usageLogs.forEach { log ->
            val appName = try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(log.packageName, 0)).toString()
            } catch (e: Exception) {
                log.packageName.split(".").last()
            }
            appCountMap[appName] = (appCountMap[appName] ?: 0f) + 1f
        }

        val entries = ArrayList<PieEntry>()
        appCountMap.forEach { (name, count) ->
            entries.add(PieEntry(count, name))
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 12f

        val data = PieData(dataSet)
        binding.pieChart.data = data
        binding.pieChart.description.isEnabled = false
        binding.pieChart.centerText = "Targeted Apps"
        binding.pieChart.setCenterTextSize(16f)
        binding.pieChart.animateY(1400)
        binding.pieChart.invalidate()
    }

    private class IntruderAdapter(
        private val list: List<Intruder>,
        private val usageLogs: List<UsageLogEntry>
    ) : RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemIntruderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            holder.binding.tvTimestamp.text = sdf.format(Date(item.timestamp))
            
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
                (holder.itemView.context as? HistoryActivity)?.refreshList()
            }

            // Bind usage logs that happened near this intruder's timestamp
            val relevantLogs = usageLogs.filter { 
                it.timestamp >= (item.timestamp - 60000) && it.timestamp <= (item.timestamp + 300000)
            }

            if (relevantLogs.isNotEmpty()) {
                holder.binding.tvUsageTitle.visibility = View.VISIBLE
                holder.binding.llUsageLogs.removeAllViews()
                relevantLogs.forEach { log ->
                    val logBinding = ItemUsageLogBinding.inflate(LayoutInflater.from(holder.itemView.context), holder.binding.llUsageLogs, false)
                    val appName = try {
                        val pm = holder.itemView.context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(log.packageName, 0)).toString()
                    } catch (e: Exception) {
                        log.packageName.split(".").last()
                    }
                    logBinding.tvAppName.text = appName
                    logBinding.tvDuration.text = "${log.duration}s"
                    holder.binding.llUsageLogs.addView(logBinding.root)
                }
            } else {
                holder.binding.tvUsageTitle.visibility = View.GONE
                holder.binding.llUsageLogs.removeAllViews()
            }
        }

        override fun getItemCount() = list.size

        class ViewHolder(val binding: ItemIntruderBinding) : RecyclerView.ViewHolder(binding.root)
    }

    fun refreshList() {
        val files = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val logStrings = sharedPrefs.getStringSet("IntruderUsageLogs", emptySet()) ?: emptySet()
        val usageLogs = logStrings.mapNotNull { log ->
            val parts = log.split(" | ")
            if (parts.size == 3) {
                UsageLogEntry(parts[0], parts[1].toLong(), parts[2].toLong())
            } else null
        }.sortedByDescending { it.timestamp }

        // Populate Top Usage Logs
        binding.llAllUsageLogs.removeAllViews()
        if (usageLogs.isEmpty()) {
            binding.cardUsageLogs.visibility = View.GONE
            binding.tvUsageLogsTitle.visibility = View.GONE
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
                logBinding.tvDuration.text = "$timeStr (${log.duration}s)"
                binding.llAllUsageLogs.addView(logBinding.root)
            }
        }

        if (files.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvIntruders.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvIntruders.visibility = View.VISIBLE
            val intruderList = files.map { Intruder(it.absolutePath, it.lastModified()) }
            binding.rvIntruders.adapter = IntruderAdapter(intruderList, usageLogs)
        }
        
        setupPieChart(usageLogs)
    }

    data class UsageLogEntry(val packageName: String, val timestamp: Long, val duration: Long)
}
