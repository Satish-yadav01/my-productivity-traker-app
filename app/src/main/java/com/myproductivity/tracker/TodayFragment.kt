package com.myproductivity.tracker

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.myproductivity.tracker.databinding.FragmentTodayBinding
import com.myproductivity.tracker.databinding.ItemActivityBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TodayFragment : Fragment(R.layout.fragment_today) {

    private var _binding: FragmentTodayBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SecureSettings
    private lateinit var store: PendingStore
    private lateinit var cache: TodayCache
    private lateinit var reportCache: ReportCache
    private lateinit var api: SheetsApi
    private lateinit var coordinator: SaveCoordinator
    private var refreshJob: Job? = null
    private val rolloverHandler = Handler(Looper.getMainLooper())
    private val dayRollover = object : Runnable {
        override fun run() {
            if (_binding == null) return
            renderFromLocal()
            refresh()
            scheduleDayRollover()
        }
    }

    private val confirmLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val msg = result.data?.getStringExtra(ConfirmActivity.EXTRA_MESSAGE)
            if (msg != null) {
                val ok = result.data?.getBooleanExtra(ConfirmActivity.EXTRA_SUCCESS, true) ?: true
                Snackbar.make(binding.root, msg, if (ok) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE)
                    .apply { if (!ok) setAction("OK") { dismiss() } }
                    .show()
                val completed = result.data?.getStringArrayExtra(ConfirmActivity.EXTRA_COMPLETED_NAMES)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (ok && completed.isNotEmpty()) {
                    CompletionCelebration.show(
                        requireContext(),
                        requireActivity().findViewById(android.R.id.content),
                        completed.joinToString(", ")
                    )
                }
            }
            refresh()
        }

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val msg = result.data?.getStringExtra(EditActivity.EXTRA_MESSAGE)
            if (msg != null) {
                val ok = result.data?.getBooleanExtra(EditActivity.EXTRA_SUCCESS, true) ?: true
                Snackbar.make(binding.root, msg, if (ok) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE)
                    .apply { if (!ok) setAction("OK") { dismiss() } }
                    .show()
                if (ok && result.data?.getBooleanExtra(EditActivity.EXTRA_COMPLETED, false) == true) {
                    CompletionCelebration.show(
                        requireContext(),
                        requireActivity().findViewById(android.R.id.content),
                        result.data?.getStringExtra(EditActivity.EXTRA_COMPLETED_NAME).orEmpty()
                    )
                }
            }
            refresh()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTodayBinding.bind(view)
        settings = SecureSettings(requireContext())
        store = PendingStore(requireContext())
        cache = TodayCache(requireContext())
        reportCache = ReportCache(requireContext())
        api = SheetsApi(settings)
        coordinator = SaveCoordinator(api, store, requireContext())

        binding.micButton.setOnClickListener { openConfirm(autoListen = true) }
        binding.typeButton.setOnClickListener { openConfirm(autoListen = false) }
        binding.tomorrowPlanButton.setOnClickListener {
            openConfirm(autoListen = false, defaultDate = Config.today().plusDays(1))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.totalTimeInfoButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.planned_time_info_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.refresh.setOnRefreshListener { refresh() }
        binding.banner.setOnClickListener {
            if (!settings.isConfigured) startActivity(Intent(requireContext(), SettingsActivity::class.java))
            else refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            renderFromLocal()
            refresh()
            scheduleDayRollover()
        }
    }

    override fun onPause() {
        rolloverHandler.removeCallbacks(dayRollover)
        super.onPause()
    }

    override fun onDestroyView() {
        rolloverHandler.removeCallbacks(dayRollover)
        refreshJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun openConfirm(autoListen: Boolean) {
        openConfirm(autoListen, null)
    }

    private fun scheduleDayRollover() {
        rolloverHandler.removeCallbacks(dayRollover)
        val nextMidnight = Config.today().plusDays(1).atStartOfDay(Config.ZONE)
            .toInstant().toEpochMilli()
        rolloverHandler.postDelayed(dayRollover, (nextMidnight - System.currentTimeMillis() + 1_000L).coerceAtLeast(1_000L))
    }

    private fun openConfirm(autoListen: Boolean, defaultDate: LocalDate?) {
        confirmLauncher.launch(
            Intent(requireContext(), ConfirmActivity::class.java)
                .putExtra(ConfirmActivity.EXTRA_AUTO_LISTEN, autoListen)
                .putExtra(ConfirmActivity.EXTRA_DEFAULT_DATE, defaultDate?.toString())
        )
    }

    private fun renderFromLocal() {
        val today = Config.today().toString()
        val tomorrow = Config.today().plusDays(1).toString()
        val cached = cache.load(today)
        val tomorrowDate = LocalDate.parse(tomorrow)
        render(
            mergeCachedAndPending(cached?.first.orEmpty(), pendingFor(today, onlyUnadded = false)),
            mergeCachedAndPending(
                reportCache.getRange(tomorrowDate, tomorrowDate),
                pendingFor(tomorrow, onlyUnadded = false)
            ),
            if (cached != null) "Last synced ${clock(cached.second)}" else "Not synced yet"
        )
    }

    private fun pendingFor(date: String, onlyUnadded: Boolean): List<Activity> =
        store.load()
            .filter { it.activity.date == date && (!onlyUnadded || it.serverId == null) }
            .map { it.activity }

    private fun mergeCachedAndPending(cached: List<Activity>, pending: List<Activity>): List<Activity> {
        val pendingIds = pending.map { it.clientId }.toSet()
        return (cached.filterNot { it.clientId in pendingIds } + pending).distinctBy { it.clientId }
    }

    private fun refresh() {
        if (!settings.isConfigured) {
            binding.refresh.isRefreshing = false
            showBanner(getString(R.string.not_configured))
            return
        }
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.refresh.isRefreshing = true
            val today = Config.today().toString()
            val tomorrow = Config.today().plusDays(1).toString()
            try {
                if (store.load().isNotEmpty()) coordinator.flush()
                val dates = api.getDates(listOf(LocalDate.parse(today), LocalDate.parse(tomorrow)))
                val server = dates[today].orEmpty()
                val tomorrowServer = dates[tomorrow].orEmpty()
                val now = System.currentTimeMillis()
                cache.save(today, server, now)
                reportCache.save(mapOf(today to server, tomorrow to tomorrowServer))
                render(
                    mergeCachedAndPending(server, pendingFor(today, onlyUnadded = true)),
                    mergeCachedAndPending(tomorrowServer, pendingFor(tomorrow, onlyUnadded = true)),
                    "Synced ${clock(now)}"
                )
            } catch (e: ApiException) {
                val cached = cache.load(today)
                val tomorrowDate = LocalDate.parse(tomorrow)
                render(
                    mergeCachedAndPending(cached?.first.orEmpty(), pendingFor(today, onlyUnadded = false)),
                    mergeCachedAndPending(
                        reportCache.getRange(tomorrowDate, tomorrowDate),
                        pendingFor(tomorrow, onlyUnadded = false)
                    ),
                    "Offline — ${e.message}"
                )
            } finally {
                _binding?.refresh?.isRefreshing = false
            }
        }
    }

    private fun render(list: List<Activity>, tomorrowList: List<Activity>, syncText: String) {
        binding.dateText.text = Config.today().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        binding.syncText.text = syncText
        binding.countText.text = list.size.toString()
        val personalActivities = list.filterNot {
            it.category.trim().equals("Work", ignoreCase = true)
        }
        val personalPlannedMinutes = personalActivities.sumOf { it.plannedMin ?: 0 }
        val personalActualMinutes = personalActivities.sumOf { it.actualMin ?: 0 }
        binding.totalText.text = formatMinutes(personalPlannedMinutes).let {
            if (it == "—") "0m" else it
        }
        binding.actualTotalText.text = formatMinutes(personalActualMinutes).let {
            if (it == "—") "0m" else it
        }

        val pendingIds = store.load().flatMap { listOfNotNull(it.activity.clientId, it.serverId) }.toSet()
        val activityReminders = ReminderStore(requireContext()).all()
        renderActivityList(binding.listContainer, list, pendingIds, activityReminders)
        renderActivityList(binding.tomorrowListContainer, tomorrowList, pendingIds, activityReminders)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.tomorrowEmptyText.visibility = if (tomorrowList.isEmpty()) View.VISIBLE else View.GONE

        val waiting = store.load().size
        when {
            !settings.isConfigured -> showBanner(getString(R.string.not_configured))
            waiting == 1 -> showBanner(getString(R.string.pending_banner, waiting))
            waiting > 1 -> showBanner(getString(R.string.pending_banner_plural, waiting))
            else -> binding.banner.visibility = View.GONE
        }
    }

    private fun renderActivityList(
        container: android.widget.LinearLayout,
        activities: List<Activity>,
        pendingIds: Set<String>,
        activityReminders: List<ActivityReminder>
    ) {
        container.removeAllViews()
        for (activity in activities) {
            val item = ItemActivityBinding.inflate(layoutInflater, container, false)
            item.itemName.text = activity.name
            item.root.setCardBackgroundColor(requireContext().getColor(statusBackgroundColor(activity.status)))
            val isPending = activity.clientId in pendingIds
            val metaText = listOfNotNull(
                activity.plannedMin?.let { getString(R.string.planned_time_short, formatMinutes(it)) },
                getString(R.string.waiting_to_upload).takeIf { isPending }
            ).joinToString(" · ")
            item.itemMeta.textSize = 12f
            val reminder = activityReminders.firstOrNull {
                it.activityId == activity.clientId || it.serverId == activity.clientId
            }
            val reminderTime = when {
                reminder != null && reminder.triggerAtMillis > System.currentTimeMillis() ->
                    DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
                        .format(Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(Config.ZONE))
                else -> activity.reminderAt?.let(ReminderScheduler::formatReminderAt)
            }
            if (reminderTime != null) {
                item.itemReminder.text = getString(R.string.remind_me_at, reminderTime)
                item.itemReminder.visibility = View.VISIBLE
            } else {
                item.itemReminder.visibility = View.GONE
            }
            item.itemPending.visibility = View.GONE
            val toggleDetails = {
                val expanded = item.itemDetails.visibility != View.VISIBLE
                val date = LocalDate.parse(activity.date)
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                item.itemDetails.text = activityDetails(activity, date)
                item.itemDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                val expandLabel = getString(if (expanded) R.string.tap_to_collapse else R.string.tap_to_expand)
                item.itemMeta.text = listOfNotNull(metaText.takeIf { it.isNotBlank() }, expandLabel)
                    .joinToString(" · ")
            }
            item.itemMeta.text = listOfNotNull(metaText.takeIf { it.isNotBlank() }, getString(R.string.tap_to_expand))
                .joinToString(" · ")
            item.root.setOnClickListener { toggleDetails() }
            item.itemMeta.setOnClickListener { toggleDetails() }
            item.editButton.setOnClickListener {
                editLauncher.launch(
                    Intent(requireContext(), EditActivity::class.java)
                        .putExtra(EditActivity.EXTRA_ACTIVITY, activity.toJson().toString())
                        .putExtra(EditActivity.EXTRA_PENDING, activity.clientId in pendingIds)
                )
            }
            item.deleteButton.visibility = View.VISIBLE
            item.deleteButton.setOnClickListener {
                confirmDelete(activity, item.deleteButton)
            }
            container.addView(item.root)
        }
    }

    private fun showBanner(text: String) {
        binding.banner.text = text
        binding.banner.visibility = View.VISIBLE
    }

    private fun confirmDelete(activity: Activity, deleteButton: View) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_activity_title)
            .setMessage(getString(R.string.delete_activity_confirmation, activity.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val queued = store.findActivity(activity.clientId)
                        val serverId = queued?.serverId ?: activity.clientId
                        if (queued?.serverId != null || queued == null) {
                            if (serverId.isBlank()) throw ApiException("This activity has no sheet ID.", false)
                            api.deleteActivity(serverId)
                        }
                        store.removeActivity(activity.clientId, serverId)
                        ReminderScheduler.cancelActivity(requireContext(), activity.clientId)
                        ReminderScheduler.cancelActivity(requireContext(), serverId)
                        cache.removeActivity(activity.date, activity.clientId, serverId, queued?.activity?.clientId)
                        Snackbar.make(binding.root, R.string.activity_deleted, Snackbar.LENGTH_LONG).show()
                        refresh()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        deleteButton.isEnabled = true
                        Snackbar.make(
                            binding.root,
                            e.message ?: getString(R.string.delete_failed),
                            Snackbar.LENGTH_INDEFINITE
                        ).setAction("OK") { }.show()
                    }
                }
            }
            .show()
    }

    private fun clock(millis: Long): String =
        DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(millis).atZone(Config.ZONE))
}

internal fun statusBackgroundColor(status: String): Int = when (status.trim().lowercase(Locale.ROOT)) {
    "completed" -> R.color.status_completed_bg
    "in progress" -> R.color.status_in_progress_bg
    else -> R.color.status_planned_bg
}

internal fun activityDetails(activity: Activity, dateLabel: String? = null): String = buildList {
    if (dateLabel != null) add("Date - $dateLabel")
    add("Category - ${activity.category.ifBlank { "No category" }}")
    add("Status - ${activity.status.ifBlank { "Unknown" }}")
    add("Priority - ${activity.priority.ifBlank { "Unknown" }}")
    activity.plannedMin?.let { add("Planned time - ${formatMinutes(it)}") }
    activity.actualMin?.let { add("Actual time - ${formatMinutes(it)}") }
}.joinToString("\n")
