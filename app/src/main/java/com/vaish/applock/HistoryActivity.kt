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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val rvIntruders = findViewById<RecyclerView>(R.id.rvIntruders)
        rvIntruders.layoutManager = LinearLayoutManager(this)

        val files = getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("intruder_") && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val intruderList = files.map { Intruder(it.absolutePath, it.lastModified()) }
        rvIntruders.adapter = IntruderAdapter(intruderList)
    }

    private class IntruderAdapter(private val list: List<Intruder>) : RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_intruder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvTimestamp.text = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
            
            val bitmap = BitmapFactory.decodeFile(item.imagePath)
            holder.ivIntruder.setImageBitmap(bitmap)
        }

        override fun getItemCount() = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIntruder: ImageView = view.findViewById(R.id.ivIntruder)
            val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        }
    }
}
