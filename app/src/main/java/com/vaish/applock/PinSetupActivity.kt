package com.vaish.applock

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vaish.applock.databinding.ActivityPinSetupBinding

class PinSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val existingPin = sharedPrefs.getString("SecurityPin", null)

        if (existingPin != null) {
            binding.tvPinTitle.text = "Change Security PIN"
            binding.etPin.hint = "Enter new PIN"
        }

        binding.btnSavePin.setOnClickListener {
            val pin = binding.etPin.text.toString()
            if (pin.length in 4..6) {
                sharedPrefs.edit().putString("SecurityPin", pin).apply()
                Toast.makeText(this, "PIN Saved Successfully", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
