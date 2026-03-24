package com.logger.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import com.logger.data.SettingsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
    
    // Track selected filters
    private var currentTypeFilter: String? = null
    private var currentPackageFilter: String? = null
    
    // Default to the start and end of the current day
    private var currentStartTimestamp: Long = 0L
    private var currentEndTimestamp: Long = 0L

    // Track which tab is active: 0=App, 1=Calls, 2=Messages
    private var activeTab = 0

    // Permission launcher for phone permissions
    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Snackbar.make(binding.root, "Phone permissions needed for call logging", Snackbar.LENGTH_LONG).show()
        }
    }

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
        setupDropdownFilters()
        setupBottomNavigation()
        checkPermissions()
        requestPhonePermissions()
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
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        when (activeTab) {
            0 -> {
                binding.filterContainer.visibility = View.VISIBLE
                observeLogs()
            }
            1 -> {
                binding.filterContainer.visibility = View.GONE
                observeCallLogs()
            }
            2 -> {
                binding.filterContainer.visibility = View.GONE
                observeSmsLogs()
            }
        }
    }

    private fun setupDropdownFilters() {
        val eventTypes = arrayOf("All Logs", "Unlocks", "App Opens", "App Closes", "App Focus")
        val typeMap = mapOf(
            "All Logs" to null,
            "Unlocks" to LogEntry.TYPE_AUTH_UNLOCK,
            "App Opens" to LogEntry.TYPE_APP_OPENED,
            "App Closes" to LogEntry.TYPE_APP_CLOSED,
            "App Focus" to LogEntry.TYPE_APP_FOCUS
        )

        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, eventTypes)
        binding.dropdownEventType.setAdapter(typeAdapter)

        binding.dropdownEventType.setOnItemClickListener { _, _, position, _ ->
            val selection = eventTypes[position]
            currentTypeFilter = typeMap[selection]
            observeLogs()
        }

        // Setup Package filter
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            dao.getDistinctPackages().collectLatest { packages ->
                // "All Packages" is the default first option
                val displayList = mutableListOf("All Packages")
                displayList.addAll(packages)

                val pkgAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, displayList)
                binding.dropdownPackage.setAdapter(pkgAdapter)

                // Re-select value if options update out from under the user
                if (currentPackageFilter == null) {
                    binding.dropdownPackage.setText("All Packages", false)
                }
            }
        }

        binding.dropdownPackage.setOnItemClickListener { _, _, position, _ ->
            val selection = binding.dropdownPackage.adapter.getItem(position) as String
            currentPackageFilter = if (selection == "All Packages") null else selection
            observeLogs()
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_app_activity -> {
                    activeTab = 0
                    binding.filterContainer.visibility = View.VISIBLE
                    observeLogs()
                    true
                }
                R.id.nav_call_history -> {
                    activeTab = 1
                    binding.filterContainer.visibility = View.GONE
                    observeCallLogs()
                    true
                }
                R.id.nav_messages -> {
                    activeTab = 2
                    binding.filterContainer.visibility = View.GONE
                    observeSmsLogs()
                    true
                }
                else -> false
            }
        }
    }

    private fun showDatePickerMenu() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setSelection(
                Pair(currentStartTimestamp, currentEndTimestamp)
            )
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            currentStartTimestamp = selection.first
            
            val endCalendar = Calendar.getInstance()
            endCalendar.timeInMillis = selection.second
            endCalendar.set(Calendar.HOUR_OF_DAY, 23)
            endCalendar.set(Calendar.MINUTE, 59)
            endCalendar.set(Calendar.SECOND, 59)
            endCalendar.set(Calendar.MILLISECOND, 999)
            currentEndTimestamp = endCalendar.timeInMillis
            
            if (activeTab == 1) {
                observeCallLogs()
            } else if (activeTab == 2) {
                observeSmsLogs()
            } else {
                observeLogs()
            }
        }

        datePicker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun observeLogs() {
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            dao.getFilteredLogs(
                type = currentTypeFilter,
                pkg = currentPackageFilter,
                startTimestamp = currentStartTimestamp,
                endTimestamp = currentEndTimestamp
            ).collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun observeCallLogs() {
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            dao.getCallLogs(
                startTimestamp = currentStartTimestamp,
                endTimestamp = currentEndTimestamp
            ).collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun observeSmsLogs() {
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            dao.getSmsLogs(
                startTimestamp = currentStartTimestamp,
                endTimestamp = currentEndTimestamp
            ).collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    // ─── Permissions & Initial Service Start ─────────────────────────

    private fun requestPhonePermissions() {
        val neededPermissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.READ_SMS)
        }

        if (neededPermissions.isNotEmpty()) {
            phonePermissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

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
        // Refresh data to match the currently selected tab
        refreshCurrentTab()
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
