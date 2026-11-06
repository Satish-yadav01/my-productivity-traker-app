package com.myproductivity.tracker

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SecureSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        settings = SecureSettings(this)

        binding.urlInput.setText(settings.apiUrl)
        binding.tokenInput.setText(settings.token)
        binding.setupGuideButton.setOnClickListener {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }

        binding.saveButton.setOnClickListener {
            if (saveIfValid()) {
                show("Saved.", ok = true)
            }
        }
        binding.testButton.setOnClickListener {
            if (!saveIfValid()) return@setOnClickListener
            binding.testButton.isEnabled = false
            show("Testing…", ok = true)
            lifecycleScope.launch {
                try {
                    val msg = SheetsApi(settings).ping()
                    show("Connected: $msg", ok = true)
                } catch (e: ApiException) {
                    show(e.message ?: "Could not connect.", ok = false)
                } finally {
                    binding.testButton.isEnabled = true
                }
            }
        }
    }

    private fun saveIfValid(): Boolean {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        val token = binding.tokenInput.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("https://")) {
            show("The URL must start with https:// (copy it from Apps Script → Deploy → Web app).", ok = false)
            return false
        }
        if (token.isEmpty()) {
            show("Enter the same value as API_SECRET in your script.", ok = false)
            return false
        }
        settings.apiUrl = url
        settings.token = token
        return true
    }

    private fun show(text: String, ok: Boolean) {
        binding.resultText.setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#B3261E"))
        binding.resultText.text = text
    }
}
