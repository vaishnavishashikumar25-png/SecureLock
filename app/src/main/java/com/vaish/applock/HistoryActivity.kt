package com.vaish.applock

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaish.applock.databinding.ActivityHistoryBinding
import com.vaish.applock.databinding.ItemIntruderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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

        val files = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvIntruders.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvIntruders.visibility = View.VISIBLE
            val intruderList = files.map { Intruder(it.absolutePath, it.lastModified()) }
            binding.rvIntruders.adapter = IntruderAdapter(intruderList)
        }
    }

    private class IntruderAdapter(private val list: List<Intruder>) : RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemIntruderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvTimestamp.text = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
            
            val bitmap = BitmapFactory.decodeFile(item.imagePath)
            holder.binding.ivIntruder.setImageBitmap(bitmap)

            holder.binding.btnDelete.setOnClickListener {
                File(item.imagePath).delete()
                (holder.itemView.context as? HistoryActivity)?.refreshList()
            }
        }

        override fun getItemCount() = list.size

        class ViewHolder(val binding: ItemIntruderBinding) : RecyclerView.ViewHolder(binding.root)
    }

    fun refreshList() {
        val files = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvIntruders.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvIntruders.visibility = View.VISIBLE
            val intruderList = files.map { Intruder(it.absolutePath, it.lastModified()) }
            binding.rvIntruders.adapter = IntruderAdapter(intruderList)
        }
    }
}
