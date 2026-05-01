package com.vaish.applock

import android.content.Context
import android.util.Log
import java.util.*
import org.json.JSONObject

class BehaviorModel(val context: Context) {
    private val prefs = context.getSharedPreferences("BehaviorPrefs", Context.MODE_PRIVATE)

    /**
     * Updates the user's behavior profile for a specific app.
     * Should only be called when we are CERTAIN the owner is using the app.
     */
    fun learn(packageName: String, duration: Long) {
        try {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            
            val statsStr = prefs.getString(packageName, null)
            val stats = if (statsStr != null) JSONObject(statsStr) else JSONObject()
            
            // Update average duration
            val avgDuration = stats.optDouble("avgDuration", 0.0)
            val count = stats.optInt("count", 0)
            val newAvg = (avgDuration * count + duration) / (count + 1)
            
            // Update hourly frequency
            val hourlyFreq = stats.optJSONObject("hourlyFreq") ?: JSONObject()
            val currentFreq = hourlyFreq.optInt(hour.toString(), 0)
            hourlyFreq.put(hour.toString(), currentFreq + 1)

            stats.put("avgDuration", newAvg)
            stats.put("count", count + 1)
            stats.put("hourlyFreq", hourlyFreq)
            stats.put("lastUsed", System.currentTimeMillis())

            prefs.edit().putString(packageName, stats.toString()).apply()
            Log.d("BehaviorModel", "Learned: $packageName | Avg: ${"%.1f".format(newAvg)}s | Hour: $hour")
        } catch (e: Exception) {
            Log.e("BehaviorModel", "Error learning behavior", e)
        }
    }

    /**
     * Checks if current usage of an app is suspicious based on history.
     */
    fun isAnomaly(packageName: String): Boolean {
        try {
            val statsStr = prefs.getString(packageName, null) ?: return false
            val stats = JSONObject(statsStr)
            
            val count = stats.optInt("count", 0)
            if (count < 10) return false // Need 10 samples for a baseline

            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            
            // 1. Time-of-day Anomaly
            val hourlyFreq = stats.optJSONObject("hourlyFreq") ?: JSONObject()
            val currentFreq = hourlyFreq.optInt(hour.toString(), 0)
            
            // Find peak usage hour
            var maxFreq = 0
            for (i in 0..23) {
                maxFreq = maxOf(maxFreq, hourlyFreq.optInt(i.toString(), 0))
            }

            // If current hour usage is very low compared to peak AND low in probability
            val probability = currentFreq.toDouble() / count
            if (probability < 0.03 && currentFreq < (maxFreq * 0.1)) {
                Log.w("BehaviorModel", "Suspicious: $packageName used at unusual hour $hour (p=${"%.3f".format(probability)})")
                return true
            }
            
            return false
        } catch (e: Exception) {
            Log.e("BehaviorModel", "Error checking anomaly", e)
            return false
        }
    }
}
