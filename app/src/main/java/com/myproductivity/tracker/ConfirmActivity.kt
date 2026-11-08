package com.myproductivity.tracker

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.ActivityConfirmBinding
import com.myproductivity.tracker.databinding.ItemDraftBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Capture + confirm in one screen:
 * say/type → edit the text → "Understand" → review/edit each activity → Save.
 * Nothing is ever sent to Google Sheets until the user taps Save here.
 */
class ConfirmActivity : AppCompatActivity(), SpeechController.Listener {

    private lateinit var binding: ActivityConfirmBinding
    private lateinit var store: PendingStore
    private lateinit var coordinator: SaveCoordinator
    private lateinit var speech: SpeechController

    private val drafts = mutableListOf<Draft>()
    private var saving = false
    private var listening = false
    private var speechBaseText = ""
    private var allowReminderScheduling = true

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) speech.start()
            else showStatus("Microphone permission denied. You can still type your activities.")
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowReminderScheduling = granted
            if (granted) requestExactAlarmsOrSave() else saveConfirmedActivities()
        }
    private val exactAlarmPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            saveConfirmedActivities()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        store = PendingStore(this)
        coordinator = SaveCoordinator(SheetsApi(SecureSettings(this)), store, this)
        speech = SpeechController(this, this)

        binding.understandButton.setOnClickListener {
            if (listening) {
                binding.understandButton.isEnabled = false
                showStatus(getString(R.string.finishing_listening))
                speech.finishListening {
                    binding.understandButton.isEnabled = true
                    parseTranscript()
                }
            } else {
                parseTranscript()
            }
        }
        binding.addBlankButton.visibility = View.VISIBLE
        binding.addBlankButton.setOnClickListener { addDraft(blankParsed()) }
        binding.saveAllButton.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drafts.isEmpty() || saving) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    AlertDialog.Builder(this@ConfirmActivity)
                        .setMessage("Discard these activities? Nothing has been saved yet.")
                        .setNegativeButton("Keep editing", null)
                        .setPositiveButton("Discard") { _, _ -> isEnabled = false; finish() }
                        .show()
                }
            }
        })

        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) {
            startListening()
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            binding.transcriptInput.requestFocus()
        }
    }

    override fun onDestroy() {
        speech.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ speech

    private fun startListening() {
        speechBaseText = ""
        setTranscript("")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) speech.start()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onListening(active: Boolean) {
        listening = active
        if (active) {
            showStatus(getString(R.string.listening))
        } else if (binding.listenStatus.text == getString(R.string.listening)) {
            binding.listenStatus.visibility = View.GONE
        }
    }

    override fun onPartial(text: String) {
        setTranscript(appendSpeech(speechBaseText, text))
    }

    override fun onFinal(text: String) {
        speechBaseText = appendSpeech(speechBaseText, text)
        setTranscript(speechBaseText)
    }

    override fun onError(message: String) = showStatus(message)

    private fun setTranscript(text: String) {
        binding.transcriptInput.setText(text)
        binding.transcriptInput.setSelection(text.length)
    }

    private fun appendSpeech(existing: String, spoken: String): String {
        val addition = spoken.trim()
        if (addition.isEmpty()) return existing
        val prefix = existing.trimEnd()
        return if (prefix.isEmpty()) addition else "$prefix\n$addition"
    }

    private fun showStatus(text: String) {
        binding.listenStatus.text = text
        binding.listenStatus.visibility = View.VISIBLE
    }

    // ----------------------------------------------------------------- parsing

    private fun parseTranscript() {
        val text = binding.transcriptInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            showStatus("Say or type your activities first.")
            return
        }
        val parsed = ActivityParser.parse(text)
        if (parsed.isEmpty()) {
            showStatus("I couldn't find an activity in that. Edit the text, or tap \"Add another\" to fill one in by hand.")
            return
        }
        binding.listenStatus.visibility = View.GONE
        drafts.clear()
        binding.draftContainer.removeAllViews()
        parsed.forEach { addDraft(it) }
    }

    private fun blankParsed() = ParsedActivity(
        name = "", category = null, date = Config.today(), plannedMin = null, actualMin = null,
        priority = "Medium", status = Config.STATUS_COMPLETED,
        issues = listOf(Issue("name", "Type the activity name.", true))
    )

    private fun addDraft(p: ParsedActivity) {
        val d = Draft(p)
        drafts += d
        binding.draftContainer.addView(d.b.root)
        renumber()
        revalidate()
    }

    private fun removeDraft(d: Draft) {
        drafts.remove(d)
        binding.draftContainer.removeView(d.b.root)
        renumber()
        revalidate()
    }

    private fun renumber() {
        drafts.forEachIndexed { i, d -> d.b.draftTitle.text = "ACTIVITY ${i + 1} OF ${drafts.size}" }
    }

    // -------------------------------------------------------------- validation

    private fun revalidate() {
        val seen = HashSet<String>()
        for (d in drafts) {
            val a = d.toActivity(categoryFallback = "")
            val fp = a.fingerprint()
            d.duplicate = a.name.isNotBlank() && (fp in seen || store.isLikelyDuplicate(a))
            seen += fp
            d.b.duplicateCheck.visibility = if (d.duplicate) View.VISIBLE else View.GONE
            d.renderIssues()
        }
        binding.saveAllButton.isEnabled = !saving && drafts.isNotEmpty() && drafts.all { it.isValid() }
    }

    // -------------------------------------------------------------------- save

    private fun save() {
        if (saving) return                       // blocks double-taps
        if (!drafts.all { it.isValid() }) return
        val hasReminders = drafts.any { it.reminderEnabled && it.status != Config.STATUS_COMPLETED }
        if (hasReminders &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            !ReminderScheduler.notificationsAllowed(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (hasReminders) {
            requestExactAlarmsOrSave()
            return
        }
        saveConfirmedActivities()
    }

    private fun requestExactAlarmsOrSave() {
        if (drafts.any { it.reminderEnabled && it.status != Config.STATUS_COMPLETED } &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !ReminderScheduler.canScheduleExactAlarms(this)
        ) {
            exactAlarmPermission.launch(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            saveConfirmedActivities()
        }
    }

    private fun saveConfirmedActivities() {
        if (saving) return
        val activities = drafts.map { draft ->
            val activity = draft.toActivity()
            if (allowReminderScheduling && draft.reminderEnabled &&
                draft.status != Config.STATUS_COMPLETED
            ) {
                ReminderScheduler.scheduleActivity(this, activity, draft.reminderDelayMinutes)
            } else {
                activity.copy(reminderAt = null)
            }
        }
        saving = true
        binding.saveAllButton.isEnabled = false
        binding.saveAllButton.text = "Saving…"

        lifecycleScope.launch {
            var message: String
            var ok: Boolean
            try {
                val r = coordinator.enqueueAndFlush(activities)
                if (r.stillPending == 0) {
                    ok = true
                    message = buildString {
                        append("Saved ${r.saved} ${if (r.saved == 1) "activity" else "activities"} to Google Sheets.")
                        if (!r.dashboardOk) append(" Dashboard refresh failed — it will update on the next save.")
                    }
                } else {
                    ok = false
                    message = "${r.saved} saved. ${r.stillPending} kept safely on this phone — " +
                        "${r.firstError ?: "couldn't reach Google Sheets"}. It will retry automatically."
                }
            } catch (e: Exception) {
                // The activities were already written to the on-disk queue before any network call.
                ok = false
                message = "Couldn't finish saving (${e.message ?: "unexpected error"}). " +
                    "Your activities are kept on this phone and will retry."
            }
            if (drafts.any { it.reminderEnabled && it.status != Config.STATUS_COMPLETED } &&
                !ReminderScheduler.notificationsAllowed(this@ConfirmActivity)
            ) {
                message += " Allow notifications in Android settings to receive activity reminders."
            } else if (drafts.any { it.reminderEnabled && it.status != Config.STATUS_COMPLETED } &&
                !ReminderScheduler.canScheduleExactAlarms(this@ConfirmActivity)
            ) {
                message += " Enable Alarms & reminders for this app in Android settings for reminders at the selected time."
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_SUCCESS, ok))
            finish()
        }
    }

    // ------------------------------------------------------------- draft card

    private inner class Draft(p: ParsedActivity) {
        val b: ItemDraftBinding = ItemDraftBinding.inflate(layoutInflater, binding.draftContainer, false)
        val clientId: String = UUID.randomUUID().toString()  // stays the same on every retry → no duplicates
        var category: String? = p.category
        var date: LocalDate = p.date
        var status: String = p.status
        var priority: String = p.priority
        var reminderEnabled = p.status != Config.STATUS_COMPLETED
        var reminderDelayMinutes = ReminderScheduler.defaultDelayMinutes(p.priority)
        private var reminderDelayManuallyChanged = false
        var duplicate = false
        private val issues = p.issues.toMutableList()

        init {
            b.nameInput.setText(p.name)
            b.plannedInput.setText(p.plannedMin?.toString() ?: "")
            b.actualInput.setText(p.actualMin?.toString() ?: "")
            updateDateLabel()

            val cats = listOf("Select category…") + Config.CATEGORIES
            b.categorySpinner.adapter = adapter(cats)
            b.categorySpinner.setSelection(category?.let { cats.indexOf(it) } ?: 0)
            b.categorySpinner.onItemSelectedListener = selected { pos ->
                val v = if (pos == 0) null else Config.CATEGORIES[pos - 1]
                if (v != category) {
                    category = v
                    if (v != null) issues.removeAll { it.field == "category" }
                    revalidate()
                }
            }

            b.statusSpinner.adapter = adapter(Config.STATUSES)
            b.statusSpinner.setSelection(Config.STATUSES.indexOf(status).coerceAtLeast(0))
            b.statusSpinner.onItemSelectedListener = selected { pos ->
                val v = Config.STATUSES[pos]
                if (v != status) {
                    status = v
                    if (status == Config.STATUS_COMPLETED) {
                        reminderEnabled = false
                        b.activityReminderSwitch.isChecked = false
                    }
                    updateReminderControls()
                    issues.removeAll { it.field == "status" }
                    revalidate()
                }
            }

            b.prioritySpinner.adapter = adapter(Config.PRIORITIES)
            b.prioritySpinner.setSelection(Config.PRIORITIES.indexOf(priority).coerceAtLeast(0))
            b.prioritySpinner.onItemSelectedListener = selected { pos ->
                val nextPriority = Config.PRIORITIES[pos]
                if (nextPriority != priority) {
                    priority = nextPriority
                    if (!reminderDelayManuallyChanged) {
                        reminderDelayMinutes = ReminderScheduler.defaultDelayMinutes(priority)
                        b.reminderDelaySeekBar.progress = reminderDelayMinutes - 1
                        updateReminderLabel()
                    }
                }
            }
            b.activityReminderSwitch.isChecked = reminderEnabled
            b.reminderDelaySeekBar.progress = reminderDelayMinutes / 15 - 1
            b.activityReminderSwitch.setOnCheckedChangeListener { _, checked ->
                reminderEnabled = checked
                updateReminderControls()
            }
            b.reminderDelaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    reminderDelayMinutes = ReminderScheduler.delayForSeekProgress(progress)
                    if (fromUser) reminderDelayManuallyChanged = true
                    updateReminderLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            updateReminderControls()

            b.nameInput.addTextChangedListener(watcher {
                if (name().isNotBlank()) issues.removeAll { it.field == "name" }
                revalidate()
            })
            b.plannedInput.addTextChangedListener(watcher { revalidate() })
            b.actualInput.addTextChangedListener(watcher { revalidate() })
            b.duplicateCheck.setOnCheckedChangeListener { _, _ -> revalidate() }

            b.dateButton.setOnClickListener {
                DatePickerDialog(
                    this@ConfirmActivity,
                    { _, y, m, day ->
                        date = LocalDate.of(y, m + 1, day)
                        issues.removeAll { it.field == "date" }
                        updateDateLabel()
                        revalidate()
                    },
                    date.year, date.monthValue - 1, date.dayOfMonth
                ).show()
            }
            b.removeButton.setOnClickListener { removeDraft(this) }
            renderIssues()
        }

        fun name() = b.nameInput.text?.toString()?.trim().orEmpty()
        private fun minutes(raw: CharSequence?): Int? = raw?.toString()?.trim()?.toIntOrNull()
        private fun minutesOk(raw: CharSequence?): Boolean {
            val s = raw?.toString()?.trim().orEmpty()
            if (s.isEmpty()) return true
            val v = s.toIntOrNull() ?: return false
            return v in 1..1440
        }

        fun toActivity(categoryFallback: String = category ?: "") = Activity(
            clientId = clientId,
            name = name(),
            category = category ?: categoryFallback,
            date = date.toString(),
            plannedMin = minutes(b.plannedInput.text),
            actualMin = minutes(b.actualInput.text),
            priority = priority,
            status = status
        )

        fun isValid(): Boolean =
            name().isNotBlank() &&
                category != null &&
                issues.none { it.blocking } &&
                minutesOk(b.plannedInput.text) && minutesOk(b.actualInput.text) &&
                (!duplicate || b.duplicateCheck.isChecked)

        fun renderIssues() {
            val lines = mutableListOf<String>()
            issues.forEach { lines += "• ${it.message}" }
            if (!minutesOk(b.plannedInput.text) || !minutesOk(b.actualInput.text)) {
                lines += "• Minutes must be a whole number between 1 and 1440."
            }
            b.issuesText.text = lines.joinToString("\n")
            b.issuesText.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun updateDateLabel() {
            val f = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
            val suffix = if (date == Config.today()) " (today)" else ""
            b.dateButton.text = "Date: ${date.format(f)}$suffix"
        }

        private fun updateReminderControls() {
            val enabled = reminderEnabled && status != Config.STATUS_COMPLETED
            b.activityReminderSwitch.isEnabled = status != Config.STATUS_COMPLETED
            b.reminderDelaySeekBar.isEnabled = enabled
            updateReminderLabel()
        }

        private fun updateReminderLabel() {
            val reminderAt = java.time.Instant.ofEpochMilli(
                System.currentTimeMillis() + reminderDelayMinutes * 60_000L
            ).atZone(Config.ZONE)
            b.reminderTimeLabel.text = getString(
                R.string.reminder_in_at_time,
                ReminderScheduler.formatDelay(reminderDelayMinutes),
                reminderAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            )
            b.reminderTimeLabel.visibility =
                if (reminderEnabled && status != Config.STATUS_COMPLETED) View.VISIBLE else View.GONE
        }

        private fun adapter(items: List<String>) =
            ArrayAdapter(this@ConfirmActivity, android.R.layout.simple_spinner_dropdown_item, items)

        private fun selected(onPick: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        private fun watcher(after: () -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = after()
        }
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "autoListen"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SUCCESS = "success"
    }
}
