package com.vaish.applock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaish.applock.databinding.ActivityAppListBinding
import com.vaish.applock.databinding.ItemAppBinding

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isLocked: Boolean
)

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val lockedApps = sharedPrefs.getStringSet("LockedPackages", emptySet()) ?: emptySet()

        val appList = apps.filter { 
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.android.settings"
        }.map { 
            AppInfo(
                it.loadLabel(pm).toString(),
                it.packageName,
                it.loadIcon(pm),
                lockedApps.contains(it.packageName)
            )
        }.sortedBy { it.name }

        adapter = AppAdapter(appList) { appInfo, isChecked ->
            val currentLocked = sharedPrefs.getStringSet("LockedPackages", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (isChecked) {
                currentLocked.add(appInfo.packageName)
            } else {
                currentLocked.remove(appInfo.packageName)
            }
            sharedPrefs.edit().putStringSet("LockedPackages", currentLocked).apply()
        }
        binding.rvApps.adapter = adapter
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
                cbLock.setOnCheckedChangeListener(null)
                cbLock.isChecked = app.isLocked
                cbLock.setOnCheckedChangeListener { _, isChecked ->
                    app.isLocked = isChecked
                    onLockChanged(app, isChecked)
                }
            }
        }

        override fun getItemCount() = apps.size
    }
}
