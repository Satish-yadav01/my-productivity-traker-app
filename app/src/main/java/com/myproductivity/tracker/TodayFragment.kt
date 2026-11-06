package com.myproductivity.tracker

import android.content.Intent
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
import java.time.format.DateTimeFormatter
import java.util.Locale

class TodayFragment : Fragment(R.layout.fragment_today) {

    private var _binding: FragmentTodayBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SecureSettings
    private lateinit var store: PendingStore
    private lateinit var cache: TodayCache
    private lateinit var api: SheetsApi
    private lateinit var coordinator: SaveCoordinator
    private var refreshJob: Job? = null

    private val confirmLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val msg = result.data?.getStringExtra(ConfirmActivity.EXTRA_MESSAGE)
            if (msg != null) {
                val ok = result.data?.getBooleanExtra(ConfirmActivity.EXTRA_SUCCESS, true) ?: true
                Snackbar.make(binding.root, msg, if (ok) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE)
                    .apply { if (!ok) setAction("OK") { dismiss() } }
                    .show()
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
            }
            refresh()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTodayBinding.bind(view)
        settings = SecureSettings(requireContext())
        store = PendingStore(requireContext())
        cache = TodayCache(requireContext())
        api = SheetsApi(settings)
        coordinator = SaveCoordinator(api, store)

        binding.micButton.setOnClickListener { openConfirm(autoListen = true) }
        binding.typeButton.setOnClickListener { openConfirm(autoListen = false) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
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
        }
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun openConfirm(autoListen: Boolean) {
        confirmLauncher.launch(
            Intent(requireContext(), ConfirmActivity::class.java)
                .putExtra(ConfirmActivity.EXTRA_AUTO_LISTEN, autoListen)
        )
    }

    private fun renderFromLocal() {
        val today = Config.today().toString()
        val cached = cache.load(today)
        val list = (cached?.first ?: emptyList()) + pendingFor(today, onlyUnadded = false)
        render(list, if (cached != null) "Last synced ${clock(cached.second)}" else "Not synced yet")
    }

    private fun pendingFor(date: String, onlyUnadded: Boolean): List<Activity> =
        store.load()
            .filter { it.activity.date == date && (!onlyUnadded || it.serverId == null) }
            .map { it.activity }

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
            try {
                if (store.load().isNotEmpty()) coordinator.flush()
                val server = api.getToday(today)
                val now = System.currentTimeMillis()
                cache.save(today, server, now)
                render(server + pendingFor(today, onlyUnadded = true), "Synced ${clock(now)}")
            } catch (e: ApiException) {
                val cached = cache.load(today)
                render(
                    (cached?.first ?: emptyList()) + pendingFor(today, onlyUnadded = false),
                    "Offline — ${e.message}"
                )
            } finally {
                _binding?.refresh?.isRefreshing = false
            }
        }
    }

    private fun render(list: List<Activity>, syncText: String) {
        binding.dateText.text = Config.today().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        binding.syncText.text = syncText
        binding.countText.text = list.size.toString()
        binding.totalText.text = formatMinutes(list.sumOf { it.plannedMin ?: 0 }).let {
            if (it == "—") "0m" else it
        }

        val pendingItems = store.load()
        val pendingIds = pendingItems.flatMap { listOfNotNull(it.activity.clientId, it.serverId) }.toSet()
        binding.listContainer.removeAllViews()
        for (activity in list) {
            val item = ItemActivityBinding.inflate(layoutInflater, binding.listContainer, false)
            item.itemName.text = activity.name
            val isPending = activity.clientId in pendingIds
            item.itemMeta.text = listOfNotNull(
                getString(R.string.tap_to_expand),
                getString(R.string.waiting_to_upload).takeIf { isPending }
            ).joinToString(" · ")
            item.itemPending.visibility = View.GONE
            item.root.setOnClickListener {
                val expanded = item.itemDetails.visibility != View.VISIBLE
                item.itemDetails.text = activityDetails(activity)
                item.itemDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                item.itemMeta.text = if (expanded) getString(R.string.tap_to_collapse)
                    else getString(R.string.tap_to_expand)
            }
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
            binding.listContainer.addView(item.root)
        }
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val waiting = store.load().size
        when {
            !settings.isConfigured -> showBanner(getString(R.string.not_configured))
            waiting == 1 -> showBanner(getString(R.string.pending_banner, waiting))
            waiting > 1 -> showBanner(getString(R.string.pending_banner_plural, waiting))
            else -> binding.banner.visibility = View.GONE
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

internal fun activityDetails(activity: Activity, dateLabel: String? = null): String = buildList {
    if (dateLabel != null) add("Date - $dateLabel")
    add("Category - ${activity.category.ifBlank { "No category" }}")
    add("Status - ${activity.status.ifBlank { "Unknown" }}")
    add("Priority - ${activity.priority.ifBlank { "Unknown" }}")
    activity.plannedMin?.let { add("Planned time - ${formatMinutes(it)}") }
    activity.actualMin?.let { add("Actual time - ${formatMinutes(it)}") }
}.joinToString("\n")
