package com.myproductivity.tracker

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.ActivityEditBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

class EditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditBinding
    private lateinit var store: PendingStore
    private lateinit var api: SheetsApi
    private lateinit var original: Activity
    private var selectedDate: LocalDate = Config.today()
    private var selectedCategory: String = ""
    private var selectedPriority: String = Config.PRIORITIES[1]
    private var selectedStatus: String = Config.STATUS_PLANNED
    private var reminderEnabled = true
    private var reminderDelayMinutes = 60
    private var saving = false
    private var allowReminderScheduling = true
    private var pendingUpdatedActivity: Activity? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowReminderScheduling = granted
            val updated = pendingUpdatedActivity
            pendingUpdatedActivity = null
            updated?.let {
                if (granted) continueUpdate(it) else persistUpdate(it)
            }
        }

    private val exactAlarmPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingUpdatedActivity?.let(::persistUpdate)
            pendingUpdatedActivity = null
        }

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
        val existingReminder = ReminderStore(this).find(original.clientId)
        reminderEnabled = original.status != Config.STATUS_COMPLETED
        reminderDelayMinutes = original.reminderAt?.let(ReminderScheduler::minutesUntilReminder)
            ?: existingReminder?.let {
            val remainingMillis = (it.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(60_000L)
            ceil(remainingMillis / 60_000.0).toInt().coerceIn(1, 720)
        } ?: ReminderScheduler.defaultDelayMinutes()
        store = PendingStore(this)
        api = SheetsApi(SecureSettings(this))

        binding.nameInput.setText(original.name.capitalizeWords())
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
        binding.statusSpinner.onItemSelectedListener = spinnerListener {
            selectedStatus = Config.STATUSES[it]
            if (selectedStatus == Config.STATUS_COMPLETED) {
                reminderEnabled = false
                binding.activityReminderSwitch.isChecked = false
            }
            updateReminderControls()
        }
        binding.activityReminderSwitch.isChecked = reminderEnabled
        binding.reminderDelaySeekBar.progress = reminderDelayMinutes - 1
        binding.activityReminderSwitch.setOnCheckedChangeListener { _, checked ->
            reminderEnabled = checked
            updateReminderControls()
        }
        binding.reminderDelaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                reminderDelayMinutes = ReminderScheduler.delayForSeekProgress(progress)
                updateReminderLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateReminderControls()
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
            name = name.capitalizeWords(),
            category = selectedCategory,
            date = selectedDate.toString(),
            plannedMin = planned,
            actualMin = actual,
            priority = selectedPriority,
            status = selectedStatus
        )
        if (reminderEnabled && selectedStatus != Config.STATUS_COMPLETED &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            !ReminderScheduler.notificationsAllowed(this)
        ) {
            pendingUpdatedActivity = updated
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueUpdate(updated)
    }

    private fun continueUpdate(updated: Activity) {
        if (reminderEnabled && selectedStatus != Config.STATUS_COMPLETED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !ReminderScheduler.canScheduleExactAlarms(this)
        ) {
            pendingUpdatedActivity = updated
            exactAlarmPermission.launch(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        persistUpdate(updated)
    }

    private fun persistUpdate(updated: Activity) {
        if (saving) return
        val activityWithReminder = ReminderScheduler.updateActivity(
            this,
            original.clientId,
            updated,
            reminderDelayMinutes.takeIf { reminderEnabled && selectedStatus != Config.STATUS_COMPLETED },
            scheduleLocally = allowReminderScheduling
        )
        saving = true
        binding.updateButton.isEnabled = false
        binding.editStatus.text = getString(R.string.updating_activity)
        lifecycleScope.launch {
            try {
                if (store.updateActivity(activityWithReminder) == null) {
                    if (activityWithReminder.clientId.isBlank()) throw ApiException("This activity has no sheet ID.", false)
                    store.addAll(
                        listOf(
                            PendingItem(
                                activity = activityWithReminder,
                                serverId = activityWithReminder.clientId,
                                timeLogged = true,
                                activityUpdated = false
                            )
                        )
                    )
                }
                val result = SaveCoordinator(api, store, this@EditActivity).flush()
                if (result.stillPending == 0) {
                    val newlyCompleted = original.status != Config.STATUS_COMPLETED &&
                        activityWithReminder.status == Config.STATUS_COMPLETED
                    val message = if (reminderEnabled && !ReminderScheduler.notificationsAllowed(this@EditActivity)) {
                        "${getString(R.string.activity_updated)} Allow notifications in Android settings to receive reminders."
                    } else if (reminderEnabled && !ReminderScheduler.canScheduleExactAlarms(this@EditActivity)) {
                        "${getString(R.string.activity_updated)} Enable Alarms & reminders for this app in Android settings for reminders at the selected time."
                    } else {
                        getString(R.string.activity_updated)
                    }
                    finishWithResult(message, true, newlyCompleted)
                } else {
                    val message = getString(
                        R.string.activity_update_pending,
                        result.firstError ?: "Unable to reach the sheet."
                    ) + if (!allowReminderScheduling && reminderEnabled) {
                        " Allow notifications in Android settings to receive reminders."
                    } else {
                        ""
                    }
                    finishWithResult(message, false)
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

    private fun updateReminderControls() {
        val enabled = reminderEnabled && selectedStatus != Config.STATUS_COMPLETED
        binding.activityReminderSwitch.isEnabled = selectedStatus != Config.STATUS_COMPLETED
        binding.reminderDelaySeekBar.isEnabled = enabled
        updateReminderLabel()
    }

    private fun updateReminderLabel() {
        binding.reminderTimeLabel.text = getString(
            R.string.reminder_in_at_time,
            ReminderScheduler.formatDelay(reminderDelayMinutes),
            ReminderScheduler.reminderPreview(selectedDate.toString(), reminderDelayMinutes)
        )
        binding.reminderTimeLabel.visibility =
            if (reminderEnabled && selectedStatus != Config.STATUS_COMPLETED) View.VISIBLE else View.GONE
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

    private fun finishWithResult(message: String, success: Boolean, completed: Boolean = false) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_SUCCESS, success)
                .putExtra(EXTRA_COMPLETED, completed)
                .putExtra(EXTRA_COMPLETED_NAME, original.name)
        )
        finish()
    }

    companion object {
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_PENDING = "pending"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_COMPLETED = "completed"
        const val EXTRA_COMPLETED_NAME = "completedName"
        private const val INVALID_MINUTES = -1
    }
}
