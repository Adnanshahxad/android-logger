package com.logger.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import com.logger.data.SettingsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.logger.LoggerApp
import com.logger.R
import com.logger.data.LogEntry
import com.logger.databinding.ActivityMainBinding
import com.logger.service.LoggerForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private var currentFilter: String? = null
    
    // Default to the start and end of the current day
    private var currentStartTimestamp: Long = 0L
    private var currentEndTimestamp: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize to today's bounds
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        currentStartTimestamp = calendar.timeInMillis
        
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        currentEndTimestamp = calendar.timeInMillis

        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        checkPermissions()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Android Sys"
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_date -> {
                showDatePickerMenu()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter()
        binding.recyclerViewLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
        }
        observeLogs()
    }

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener { applyFilter(null) }
        binding.chipAuth.setOnClickListener { applyFilter(LogEntry.TYPE_AUTH_UNLOCK) }
        binding.chipAppOpen.setOnClickListener { applyFilter(LogEntry.TYPE_APP_OPENED) }
        binding.chipAppClose.setOnClickListener { applyFilter(LogEntry.TYPE_APP_CLOSED) }
        binding.chipFocus.setOnClickListener { applyFilter(LogEntry.TYPE_APP_FOCUS) }
    }

    private fun applyFilter(type: String?) {
        currentFilter = type
        observeLogs()
    }

    private fun showDatePickerMenu() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setSelection(
                Pair(currentStartTimestamp, currentEndTimestamp)
            )
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            // The selection gives timestamps in UTC at 00:00:00 of the selected days.
            // We convert the end date to 23:59:59.999 of that specific day.
            currentStartTimestamp = selection.first
            
            val endCalendar = Calendar.getInstance()
            endCalendar.timeInMillis = selection.second
            endCalendar.set(Calendar.HOUR_OF_DAY, 23)
            endCalendar.set(Calendar.MINUTE, 59)
            endCalendar.set(Calendar.SECOND, 59)
            endCalendar.set(Calendar.MILLISECOND, 999)
            currentEndTimestamp = endCalendar.timeInMillis
            
            observeLogs()
        }

        datePicker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun observeLogs() {
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            val flow = if (currentFilter != null) {
                dao.getLogsByType(currentFilter!!, currentStartTimestamp, currentEndTimestamp)
            } else {
                dao.getAllLogs(currentStartTimestamp, currentEndTimestamp)
            }
            flow.collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    // ─── Permissions & Initial Service Start ─────────────────────────

    private fun checkPermissions() {
        // 1. Check Usage Stats permission
        if (!hasUsageStatsPermission()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permission Required")
                .setMessage(
                    "This app needs \"Usage Access\" permission to track which apps are opened and closed.\n\n" +
                    "Please find \"Android Sys\" in the list and enable access."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setCancelable(false)
                .show()
            return
        }

        // 2. Start the service if enabled in settings
        val settings = SettingsManager(this)
        if (settings.isLoggerEnabled) {
            startLogging()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from settings
        val settings = SettingsManager(this)
        if (hasUsageStatsPermission() && settings.isLoggerEnabled) {
            startLogging()
        }
    }

    private fun startLogging() {
        LoggerForegroundService.start(this)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
