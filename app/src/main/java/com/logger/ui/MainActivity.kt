package com.logger.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.logger.LoggerApp
import com.logger.R
import com.logger.data.LogEntry
import com.logger.databinding.ActivityMainBinding
import com.logger.service.LoggerForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private var currentFilter: String? = null
    private var isServiceRunning = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* We proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        setupFab()
        checkPermissions()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "System Services"
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

    private fun observeLogs() {
        val dao = (application as LoggerApp).database.logDao()
        lifecycleScope.launch {
            val flow = if (currentFilter != null) {
                dao.getLogsByType(currentFilter!!)
            } else {
                dao.getAllLogs()
            }
            flow.collectLatest { logs ->
                logAdapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupFab() {
        // Toggle service button
        binding.fabToggleService.setOnClickListener {
            if (isServiceRunning) {
                LoggerForegroundService.stop(this)
                isServiceRunning = false
                binding.fabToggleService.setImageResource(R.drawable.ic_play)
                Snackbar.make(binding.root, "Logger stopped", Snackbar.LENGTH_SHORT).show()
            } else {
                LoggerForegroundService.start(this)
                isServiceRunning = true
                binding.fabToggleService.setImageResource(R.drawable.ic_stop)
                Snackbar.make(binding.root, "Logger started", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Clear logs button
        binding.fabClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear All Logs")
                .setMessage("Are you sure you want to delete all log entries?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        (application as LoggerApp).database.logDao().clearAllLogs()
                        Snackbar.make(binding.root, "Logs cleared", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────

    private fun checkPermissions() {
        // 1. Check Usage Stats permission
        if (!hasUsageStatsPermission()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permission Required")
                .setMessage(
                    "This app needs \"Usage Access\" permission to track which apps are opened and closed.\n\n" +
                    "Please find \"System Services\" in the list and enable access."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setCancelable(false)
                .show()
            return
        }

        // 2. Check notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 3. Start the service
        startLogging()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from settings
        if (hasUsageStatsPermission() && !isServiceRunning) {
            startLogging()
        }
    }

    private fun startLogging() {
        LoggerForegroundService.start(this)
        isServiceRunning = true
        binding.fabToggleService.setImageResource(R.drawable.ic_stop)
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
