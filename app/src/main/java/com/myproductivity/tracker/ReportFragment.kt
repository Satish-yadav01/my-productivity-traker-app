package com.myproductivity.tracker

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.myproductivity.tracker.databinding.FragmentReportBinding
import com.myproductivity.tracker.databinding.ItemActivityBinding
import com.myproductivity.tracker.databinding.ItemReportCategoryBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class ReportFragment : Fragment(R.layout.fragment_report) {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private var mode: String = MODE_RANGE
    private lateinit var api: SheetsApi
    private lateinit var reportCache: ReportCache
    private lateinit var startDate: LocalDate
    private lateinit var endDate: LocalDate
    private lateinit var monthOptions: List<LocalDate>
    private val selectedCategories = mutableSetOf<String>()
    private val selectedStatuses = mutableSetOf<String>()
    private val selectedPriorities = mutableSetOf<String>()
    private var minPlannedMinutes: Int? = null
    private var maxPlannedMinutes: Int? = null
    private var loadingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE) ?: MODE_RANGE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentReportBinding.bind(view)
        api = SheetsApi(SecureSettings(requireContext()))
        reportCache = ReportCache(requireContext())
        binding.reportTitle.text = when (mode) {
            MODE_WEEKLY -> getString(R.string.weekly_report_title)
            MODE_MONTHLY -> getString(R.string.monthly_report_title)
            else -> getString(R.string.activities_tab)
        }

        if (mode == MODE_RANGE) setupRangeControls() else setupPeriodControls()
        setupFilters()
        binding.loadButton.setOnClickListener { loadReport() }
        loadReport()
    }

    override fun onDestroyView() {
        loadingJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun setupRangeControls() {
        binding.dateRangeControls.visibility = View.VISIBLE
        binding.periodControls.visibility = View.GONE
        binding.monthSpinner.visibility = View.VISIBLE
        val today = Config.today()
        monthOptions = (0..119).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }
        startDate = today.withDayOfMonth(1)
        endDate = today
        val labels = listOf(getString(R.string.custom_date_range)) +
            monthOptions.map { it.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) }
        binding.monthSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.monthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val month = monthOptions[position - 1]
                    startDate = month.withDayOfMonth(1)
                    endDate = month.withDayOfMonth(month.lengthOfMonth())
                    updateDateLabels()
                    loadReport()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.startDateButton.setOnClickListener { chooseDate(isStart = true) }
        binding.endDateButton.setOnClickListener { chooseDate(isStart = false) }
        updateDateLabels()
    }

    private fun setupPeriodControls() {
        binding.dateRangeControls.visibility = View.VISIBLE
        binding.monthSpinner.visibility = View.GONE
        binding.periodControls.visibility = View.VISIBLE
        startDate = periodStart(Config.today())
        endDate = periodEnd(startDate)
        binding.previousPeriodButton.setOnClickListener { shiftPeriod(-1) }
        binding.nextPeriodButton.setOnClickListener { shiftPeriod(1) }
        binding.startDateButton.setOnClickListener { chooseDate(isStart = true) }
        binding.endDateButton.setOnClickListener { chooseDate(isStart = false) }
        updatePeriodLabel()
    }

    private fun setupFilters() {
        binding.dateFilterButton.setOnClickListener { chooseDateRange() }
        binding.categoryFilterButton.setOnClickListener {
            showMultiSelectDialog(
                title = getString(R.string.category),
                options = Config.CATEGORIES,
                selected = selectedCategories
            ) {
                updateFilterButtonLabels()
                loadReport()
            }
        }
        binding.statusFilterButton.setOnClickListener {
            showMultiSelectDialog(
                title = getString(R.string.status),
                options = Config.STATUSES,
                selected = selectedStatuses
            ) {
                updateFilterButtonLabels()
                loadReport()
            }
        }
        binding.priorityFilterButton.setOnClickListener {
            showMultiSelectDialog(
                title = getString(R.string.priority),
                options = Config.PRIORITIES,
                selected = selectedPriorities
            ) {
                updateFilterButtonLabels()
                loadReport()
            }
        }
        binding.plannedTimeFilterButton.setOnClickListener { showPlannedTimeDialog() }
        binding.clearFiltersButton.setOnClickListener { clearFilters() }
        updateFilterButtonLabels()
    }

    private fun showMultiSelectDialog(
        title: String,
        options: List<String>,
        selected: MutableSet<String>,
        onChanged: () -> Unit
    ) {
        val checked = options.map { it in selected }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected.add(options[which]) else selected.remove(options[which])
            }
            .setNeutralButton(R.string.select_all) { _, _ ->
                selected.addAll(options)
                onChanged()
            }
            .setNegativeButton(R.string.clear_filter) { _, _ ->
                selected.clear()
                onChanged()
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onChanged() }
            .show()
    }

    private fun updateFilterButtonLabels() {
        binding.dateFilterButton.contentDescription =
            getString(R.string.date_filter_description, displayDate(startDate), displayDate(endDate))
        binding.categoryFilterButton.contentDescription =
            getString(R.string.category_filter_selected, selectedLabel(selectedCategories))
        binding.statusFilterButton.contentDescription =
            getString(R.string.status_filter_selected, selectedLabel(selectedStatuses))
        binding.priorityFilterButton.contentDescription =
            getString(R.string.priority_filter_selected, selectedLabel(selectedPriorities))
        val durationLabel = when {
            minPlannedMinutes == null && maxPlannedMinutes == null -> getString(R.string.all_filter_values)
            minPlannedMinutes != null && maxPlannedMinutes != null ->
                "${minPlannedMinutes}–${maxPlannedMinutes} min"
            minPlannedMinutes != null -> "≥ $minPlannedMinutes min"
            else -> "≤ $maxPlannedMinutes min"
        }
        binding.plannedTimeFilterButton.contentDescription =
            getString(R.string.planned_time_filter_description, durationLabel)
    }

    private fun selectedLabel(selected: Set<String>): String =
        selected.sorted().joinToString(", ").ifBlank { getString(R.string.all_filter_values) }

    private fun showPlannedTimeDialog() {
        val minInput = EditText(requireContext()).apply {
            hint = getString(R.string.min_planned_minutes)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            minPlannedMinutes?.let { setText(it.toString()) }
        }
        val maxInput = EditText(requireContext()).apply {
            hint = getString(R.string.max_planned_minutes)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxPlannedMinutes?.let { setText(it.toString()) }
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(minInput)
            addView(maxInput)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.planned_time_filter)
            .setView(content)
            .setNegativeButton(R.string.clear_filter, null)
            .setNeutralButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                minPlannedMinutes = null
                maxPlannedMinutes = null
                updateFilterButtonLabels()
                dialog.dismiss()
                loadReport()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minimum = minInput.text.toString().toIntOrNull()
                val maximum = maxInput.text.toString().toIntOrNull()
                if ((!minInput.text.isNullOrBlank() && minimum == null) ||
                    (!maxInput.text.isNullOrBlank() && maximum == null) ||
                    (minimum != null && maximum != null && minimum > maximum)
                ) {
                    minInput.error = getString(R.string.filter_minutes_invalid)
                    return@setOnClickListener
                }
                minPlannedMinutes = minimum
                maxPlannedMinutes = maximum
                updateFilterButtonLabels()
                dialog.dismiss()
                loadReport()
            }
        }
        dialog.show()
    }

    private fun chooseDateRange() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                startDate = LocalDate.of(year, month + 1, day)
                DatePickerDialog(
                    requireContext(),
                    { _, endYear, endMonth, endDay ->
                        endDate = LocalDate.of(endYear, endMonth + 1, endDay)
                        if (endDate.isBefore(startDate)) {
                            binding.reportStatus.text = getString(R.string.end_before_start)
                        } else {
                            updateDateLabels()
                            if (mode != MODE_RANGE) {
                                binding.periodLabel.text = "${displayDate(startDate)} – ${displayDate(endDate)}"
                            }
                            updateFilterButtonLabels()
                            loadReport()
                        }
                    },
                    endDate.year,
                    endDate.monthValue - 1,
                    endDate.dayOfMonth
                ).show()
            },
            startDate.year,
            startDate.monthValue - 1,
            startDate.dayOfMonth
        ).show()
    }

    private fun clearFilters() {
        selectedCategories.clear()
        selectedStatuses.clear()
        selectedPriorities.clear()
        minPlannedMinutes = null
        maxPlannedMinutes = null
        when (mode) {
            MODE_RANGE -> {
                startDate = Config.today().withDayOfMonth(1)
                endDate = Config.today()
                binding.monthSpinner.setSelection(0)
            }
            MODE_WEEKLY -> {
                startDate = periodStart(Config.today())
                endDate = periodEnd(startDate)
            }
            MODE_MONTHLY -> {
                startDate = periodStart(Config.today())
                endDate = periodEnd(startDate)
            }
        }
        updateDateLabels()
        if (mode != MODE_RANGE) updatePeriodLabel()
        updateFilterButtonLabels()
        Snackbar.make(binding.root, R.string.filters_cleared, Snackbar.LENGTH_SHORT).show()
        loadReport()
    }

    private fun chooseDate(isStart: Boolean) {
        val initial = if (isStart) startDate else endDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = LocalDate.of(year, month + 1, day)
                if (isStart) startDate = selected else endDate = selected
                if (mode == MODE_RANGE) binding.monthSpinner.setSelection(0)
                updateDateLabels()
                if (mode != MODE_RANGE) {
                    binding.periodLabel.text = "${displayDate(startDate)} – ${displayDate(endDate)}"
                }
                loadReport()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).show()
    }

    private fun shiftPeriod(amount: Long) {
        val newAnchor = if (mode == MODE_WEEKLY) startDate.plusWeeks(amount) else startDate.plusMonths(amount)
        startDate = periodStart(newAnchor)
        endDate = periodEnd(startDate)
        updatePeriodLabel()
        loadReport()
    }

    private fun periodStart(date: LocalDate): LocalDate =
        if (mode == MODE_WEEKLY) date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        else date.withDayOfMonth(1)

    private fun periodEnd(start: LocalDate): LocalDate =
        if (mode == MODE_WEEKLY) start.plusDays(6)
        else start.withDayOfMonth(start.lengthOfMonth())

    private fun updateDateLabels() {
        binding.startDateButton.text = getString(R.string.short_start_date_label, shortDate(startDate))
        binding.endDateButton.text = getString(R.string.short_end_date_label, shortDate(endDate))
        binding.reportSubtitle.text = getString(
            R.string.activities_between,
            displayDate(startDate),
            displayDate(endDate)
        )
    }

    private fun updatePeriodLabel() {
        val label = if (mode == MODE_WEEKLY) {
            "${shortDate(startDate)} – ${displayDate(endDate)}"
        } else {
            startDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        }
        binding.periodLabel.text = label
        updateDateLabels()
    }

    private fun loadReport() {
        if (endDate.isBefore(startDate)) {
            binding.reportStatus.text = getString(R.string.end_before_start)
            return
        }
        if (!SecureSettings(requireContext()).isConfigured) {
            binding.reportStatus.text = getString(R.string.not_configured)
            return
        }
        loadingJob?.cancel()
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.loadButton.isEnabled = false
            binding.reportProgress.visibility = View.VISIBLE
            binding.reportStatus.text = getString(R.string.loading_report)
            try {
                val missingDates = reportCache.missingDates(startDate, endDate)
                if (missingDates.isNotEmpty()) {
                    val fetchedByDate = api.getDates(missingDates)
                    reportCache.save(fetchedByDate)
                }
                val activities = filterActivities(
                    reportCache.getRange(startDate, endDate),
                    ActivityFilters(
                        categories = selectedCategories.toSet(),
                        statuses = selectedStatuses.toSet(),
                        priorities = selectedPriorities.toSet(),
                        minPlannedMinutes = minPlannedMinutes,
                        maxPlannedMinutes = maxPlannedMinutes
                    )
                )
                render(activities)
                binding.reportStatus.text = if (
                    (minPlannedMinutes != null || maxPlannedMinutes != null) &&
                    activities.any { it.plannedMin == null }
                ) getString(R.string.filter_minutes_missing) else getString(R.string.report_loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.reportStatus.text = e.message ?: getString(R.string.report_failed)
                binding.reportCount.text = ""
                binding.reportTotal.text = ""
                binding.categoryBreakdown.removeAllViews()
                binding.statusChart.setData(emptyList(), emptyList())
                binding.reportList.removeAllViews()
            } finally {
                _binding?.let {
                    it.loadButton.isEnabled = true
                    it.reportProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun render(activities: List<Activity>) {
        binding.reportCount.text = resources.getQuantityString(
            R.plurals.report_activity_count,
            activities.size,
            activities.size
        )
        val totalPlannedMinutes = activities.sumOf { it.plannedMin ?: 0 }
        val totalActualMinutes = activities.sumOf { it.actualMin ?: 0 }
        binding.reportTotal.text = getString(
            R.string.report_time_totals,
            formatMinutes(totalPlannedMinutes),
            formatMinutes(totalActualMinutes)
        )
        binding.categoryBreakdown.removeAllViews()
        activities
            .groupBy { it.category.ifBlank { getString(R.string.no_category) } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (category, categoryActivities) ->
                val categoryItem = ItemReportCategoryBinding.inflate(
                    layoutInflater,
                    binding.categoryBreakdown,
                    false
                )
                categoryItem.categoryName.text = category
                categoryItem.categoryCount.text = resources.getQuantityString(
                    R.plurals.report_activity_count,
                    categoryActivities.size,
                    categoryActivities.size
                )
                categoryItem.plannedTime.text = formatMinutes(
                    categoryActivities.sumOf { it.plannedMin ?: 0 }
                )
                categoryItem.actualTime.text = formatMinutes(
                    categoryActivities.sumOf { it.actualMin ?: 0 }
                )
                binding.categoryBreakdown.addView(categoryItem.root)
            }

        val planned = activities.filter { it.status.equals(Config.STATUS_PLANNED, ignoreCase = true) }
        val inProgress = activities.filter { it.status.equals(Config.STATUS_IN_PROGRESS, ignoreCase = true) }
        val completed = activities.filter { it.status.equals(Config.STATUS_COMPLETED, ignoreCase = true) }
        binding.statusChart.setData(
            listOf(
                getString(R.string.planned_status),
                getString(R.string.in_progress_status),
                getString(R.string.completed_status)
            ),
            listOf(planned.size, inProgress.size, completed.size)
        )

        binding.reportList.removeAllViews()
        val sorted = activities.sortedWith(compareBy<Activity> { it.date }.thenBy { it.name.lowercase(Locale.ROOT) })
        if (sorted.isEmpty()) {
            binding.reportList.addView(android.widget.TextView(requireContext()).apply {
                text = getString(R.string.no_activities_in_period)
                setTextColor(resources.getColor(R.color.muted, null))
                setPadding(0, 20, 0, 20)
            })
            return
        }
        sorted.forEach { activity ->
            val item = ItemActivityBinding.inflate(layoutInflater, binding.reportList, false)
            item.itemName.text = activity.name
            item.root.setCardBackgroundColor(requireContext().getColor(statusBackgroundColor(activity.status)))
            val date = displayDate(LocalDate.parse(activity.date))
            item.itemMeta.text = "$date · ${getString(R.string.tap_to_expand)}"
            item.itemPending.visibility = View.GONE
            item.root.setOnClickListener {
                val expanded = item.itemDetails.visibility != View.VISIBLE
                item.itemDetails.text = activityDetails(activity, date)
                item.itemDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                item.itemMeta.text = if (expanded) "$date · ${getString(R.string.tap_to_collapse)}"
                    else "$date · ${getString(R.string.tap_to_expand)}"
            }
            item.editButton.visibility = View.GONE
            binding.reportList.addView(item.root)
        }
    }

    private fun displayDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    private fun shortDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

    companion object {
        const val MODE_RANGE = "range"
        const val MODE_WEEKLY = "weekly"
        const val MODE_MONTHLY = "monthly"
        private const val ARG_MODE = "mode"

        fun newInstance(mode: String) = ReportFragment().apply {
            arguments = Bundle().putMode(mode)
        }

        private fun Bundle.putMode(mode: String): Bundle = apply { putString(ARG_MODE, mode) }
    }

}

internal data class ActivityFilters(
    val categories: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val priorities: Set<String> = emptySet(),
    val minPlannedMinutes: Int? = null,
    val maxPlannedMinutes: Int? = null
)

internal fun filterActivities(activities: List<Activity>, filters: ActivityFilters): List<Activity> =
    activities.filter { activity ->
        (filters.categories.isEmpty() || activity.category in filters.categories) &&
            (filters.statuses.isEmpty() || activity.status in filters.statuses) &&
            (filters.priorities.isEmpty() || activity.priority in filters.priorities) &&
            (filters.minPlannedMinutes == null && filters.maxPlannedMinutes == null ||
                activity.plannedMin?.let { minutes ->
                    (filters.minPlannedMinutes == null || minutes >= filters.minPlannedMinutes) &&
                        (filters.maxPlannedMinutes == null || minutes <= filters.maxPlannedMinutes)
                } == true)
    }
