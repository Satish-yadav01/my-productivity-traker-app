package com.myproductivity.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.ActivitySetupGuideBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

class SetupGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupGuideBinding
    private lateinit var settings: SecureSettings
    private var code: String = ""

    private val createTemplateDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME_TYPE)) { uri ->
            if (uri != null) saveTemplate(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        settings = SecureSettings(this)
        code = assets.open("Code.gs").bufferedReader().use { it.readText() }

        binding.generateSecretButton.setOnClickListener {
            if (settings.token.isBlank()) {
                settings.token = generateSecret()
                Toast.makeText(this, R.string.secret_generated, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.secret_already_generated, Toast.LENGTH_LONG).show()
            }
        }
        binding.viewCodeButton.setOnClickListener {
            val visible = binding.codeText.visibility != View.VISIBLE
            if (visible) binding.codeText.text = code
            binding.codeText.visibility = if (visible) View.VISIBLE else View.GONE
            binding.viewCodeButton.setText(if (visible) R.string.hide_code else R.string.view_code)
        }
        binding.copyCodeButton.setOnClickListener {
            val spreadsheetId = SpreadsheetSetup.extractSpreadsheetId(
                binding.spreadsheetUrlInput.text?.toString().orEmpty()
            )
            if (spreadsheetId == null) {
                binding.spreadsheetUrlInput.error = getString(R.string.invalid_spreadsheet_url)
                return@setOnClickListener
            }
            val secret = settings.token
            if (secret.isBlank()) {
                Toast.makeText(this, R.string.generate_secret_first, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val configuredCode = SpreadsheetSetup.buildConfiguredCode(code, spreadsheetId, secret)
            copyToClipboard("Code.gs", configuredCode)
            Toast.makeText(this, R.string.ready_code_copied, Toast.LENGTH_LONG).show()
        }
        binding.downloadTemplateButton.setOnClickListener {
            createTemplateDocument.launch(DEFAULT_TEMPLATE_NAME)
        }
        binding.openSettingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun saveTemplate(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { TemplateWorkbook.write(it) }
                        ?: error("Could not open the selected location for writing.")
                }
                Toast.makeText(this@SetupGuideActivity, R.string.template_downloaded, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SetupGuideActivity,
                    getString(R.string.template_download_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private companion object {
        const val XLSX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DEFAULT_TEMPLATE_NAME = "ProductivityTrackerTemplate.xlsx"
    }
}
