package com.myproductivity.tracker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.myproductivity.tracker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedTab = R.id.nav_today

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        ReminderScheduler.scheduleAllActivities(this)
        ReminderScheduler.scheduleDaily(this)

        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB) ?: R.id.nav_today
        binding.bottomNavigation.selectedItemId = selectedTab
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId != selectedTab) {
                selectedTab = item.itemId
                showTab(item.itemId)
            }
            true
        }
        if (savedInstanceState == null) {
            showTab(selectedTab)
            showFirstLaunchGuideIfNeeded()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tabId: Int) {
        val fragment: Fragment = when (tabId) {
            R.id.nav_activities -> ReportFragment.newInstance(ReportFragment.MODE_RANGE)
            R.id.nav_weekly -> ReportFragment.newInstance(ReportFragment.MODE_WEEKLY)
            R.id.nav_monthly -> ReportFragment.newInstance(ReportFragment.MODE_MONTHLY)
            else -> TodayFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun showFirstLaunchGuideIfNeeded() {
        val prefs = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_GUIDE_SHOWN, false) || SecureSettings(this).isConfigured) return
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_setup_title)
            .setMessage(R.string.welcome_setup_message)
            .setPositiveButton(R.string.open_setup_guide) { _, _ ->
                startActivity(Intent(this, SetupGuideActivity::class.java))
            }
            .setNegativeButton(R.string.setup_later, null)
            .show()
    }

    companion object {
        private const val STATE_SELECTED_TAB = "selectedTab"
        private const val PREFS_ONBOARDING = "onboarding"
        private const val KEY_GUIDE_SHOWN = "setup_guide_shown"
    }
}
