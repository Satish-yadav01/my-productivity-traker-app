package com.myproductivity.tracker

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.ActivityEditBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class EditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditBinding
    private lateinit var store: PendingStore
    private lateinit var api: SheetsApi
    private lateinit var original: Activity
    private var selectedDate: LocalDate = Config.today()
    private var selectedCategory: String = ""
    private var selectedPriority: String = Config.PRIORITIES[1]
    private var selectedStatus: String = Config.STATUS_PLANNED
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        original = try {
            Activity.fromJson(JSONObject(intent.getStringExtra(EXTRA_ACTIVITY) ?: error("Missing activity")))
        } catch (e: Exception) {
            finish()
            return
        }
        selectedDate = try {
            LocalDate.parse(original.date)
        } catch (e: Exception) {
            Config.today()
        }
        selectedCategory = original.category
        selectedPriority = original.priority
        selectedStatus = original.status
        store = PendingStore(this)
        api = SheetsApi(SecureSettings(this))

        binding.nameInput.setText(original.name)
        binding.plannedInput.setText(original.plannedMin?.toString().orEmpty())
        binding.actualInput.setText(original.actualMin?.toString().orEmpty())
        val categories = buildList {
            addAll(Config.CATEGORIES)
            if (selectedCategory.isNotBlank() && selectedCategory !in Config.CATEGORIES) {
                add(selectedCategory)
            }
        }
        binding.categorySpinner.adapter = adapter(categories)
        binding.prioritySpinner.adapter = adapter(Config.PRIORITIES)
        binding.statusSpinner.adapter = adapter(Config.STATUSES)
        binding.categorySpinner.setSelection(categories.indexOf(selectedCategory).coerceAtLeast(0))
        binding.prioritySpinner.setSelection(Config.PRIORITIES.indexOf(selectedPriority).coerceAtLeast(0))
        binding.statusSpinner.setSelection(Config.STATUSES.indexOf(selectedStatus).coerceAtLeast(0))
        binding.categorySpinner.onItemSelectedListener = spinnerListener { selectedCategory = categories[it] }
        binding.prioritySpinner.onItemSelectedListener = spinnerListener { selectedPriority = Config.PRIORITIES[it] }
        binding.statusSpinner.onItemSelectedListener = spinnerListener { selectedStatus = Config.STATUSES[it] }
        binding.dateButton.setOnClickListener { chooseDate() }
        binding.updateButton.setOnClickListener { update() }
        updateDateLabel()
    }

    private fun update() {
        if (saving) return
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val planned = parseMinutes(binding.plannedInput.text?.toString().orEmpty())
        val actual = parseMinutes(binding.actualInput.text?.toString().orEmpty())
        if (name.isBlank()) {
            binding.editStatus.text = getString(R.string.activity_name_required)
            return
        }
        if (planned == INVALID_MINUTES || actual == INVALID_MINUTES) {
            binding.editStatus.text = getString(R.string.invalid_minutes)
            return
        }
        val updated = original.copy(
            name = name,
            category = selectedCategory,
            date = selectedDate.toString(),
            plannedMin = planned,
            actualMin = actual,
            priority = selectedPriority,
            status = selectedStatus
        )
        saving = true
        binding.updateButton.isEnabled = false
        binding.editStatus.text = getString(R.string.updating_activity)
        lifecycleScope.launch {
            try {
                if (store.updateActivity(updated) == null) {
                    if (updated.clientId.isBlank()) throw ApiException("This activity has no sheet ID.", false)
                    store.addAll(
                        listOf(
                            PendingItem(
                                activity = updated,
                                serverId = updated.clientId,
                                timeLogged = true,
                                activityUpdated = false
                            )
                        )
                    )
                }
                val result = SaveCoordinator(api, store).flush()
                if (result.stillPending == 0) {
                    finishWithResult(getString(R.string.activity_updated), true)
                } else {
                    finishWithResult(
                        getString(R.string.activity_update_pending, result.firstError ?: "Unable to reach the sheet."),
                        false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.editStatus.text = e.message ?: getString(R.string.report_failed)
                saving = false
                binding.updateButton.isEnabled = true
            }
        }
    }

    private fun chooseDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                updateDateLabel()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    private fun updateDateLabel() {
        binding.dateButton.text = getString(
            R.string.start_date_label,
            selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))
        )
    }

    private fun parseMinutes(value: String): Int? {
        if (value.isBlank()) return null
        return value.toIntOrNull()?.takeIf { it in 1..1440 } ?: INVALID_MINUTES
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun spinnerListener(onSelected: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onSelected(position)

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    private fun finishWithResult(message: String, success: Boolean) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_SUCCESS, success)
        )
        finish()
    }

    companion object {
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_PENDING = "pending"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SUCCESS = "success"
        private const val INVALID_MINUTES = -1
    }
}
