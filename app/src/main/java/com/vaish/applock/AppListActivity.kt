package com.vaish.applock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaish.applock.databinding.ActivityAppListBinding
import com.vaish.applock.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isLocked: Boolean
)

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var adapter: AppAdapter
    private var isWhitelistMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)



        binding.rvApps.layoutManager = LinearLayoutManager(this)
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val appList = withContext(Dispatchers.Default) {
                val pm = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                
                val prefKey = "LockedPackages"
                val selectedApps = sharedPrefs.getStringSet(prefKey, emptySet()) ?: emptySet()

                apps.map {
                    AppInfo(
                        it.loadLabel(pm).toString(),
                        it.packageName,
                        it.loadIcon(pm),
                        selectedApps.contains(it.packageName)
                    )
                }.sortedBy { it.name }
            }

            val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            val prefKey = "LockedPackages"

            adapter = AppAdapter(appList) { appInfo, isChecked ->
                val currentSelected = sharedPrefs.getStringSet(prefKey, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (isChecked) {
                    currentSelected.add(appInfo.packageName)
                } else {
                    currentSelected.remove(appInfo.packageName)
                }
                sharedPrefs.edit().putStringSet(prefKey, currentSelected).apply()
                
                // Update Select All switch state without triggering listener
                binding.switchSelectAll.setOnCheckedChangeListener(null)
                binding.switchSelectAll.isChecked = appList.all { it.isLocked }
                setupSelectAllListener(appList, prefKey)
            }
            binding.rvApps.adapter = adapter

            // Initialize Select All switch
            binding.switchSelectAll.isChecked = appList.all { it.isLocked }
            setupSelectAllListener(appList, prefKey)
        }
    }

    private fun setupSelectAllListener(appList: List<AppInfo>, prefKey: String) {
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        binding.switchSelectAll.setOnCheckedChangeListener { _, isChecked ->
            appList.forEach { it.isLocked = isChecked }
            val newSelection = if (isChecked) {
                appList.map { it.packageName }.toSet()
            } else {
                emptySet()
            }
            sharedPrefs.edit().putStringSet(prefKey, newSelection).apply()
            adapter.notifyDataSetChanged()
        }
    }

    class AppAdapter(private val apps: List<AppInfo>, private val onLockChanged: (AppInfo, Boolean) -> Unit) :
        RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.binding.apply {
                tvAppName.text = app.name
                tvPackageName.text = app.packageName
                ivAppIcon.setImageDrawable(app.icon)
                switchLock.setOnCheckedChangeListener(null)
                switchLock.isChecked = app.isLocked
                switchLock.setOnCheckedChangeListener { _, isChecked ->
                    app.isLocked = isChecked
                    onLockChanged(app, isChecked)
                }
            }
        }

        override fun getItemCount() = apps.size
    }
}
